# Multi-actor `actor` filter — Execution Plan

## Context

The Query API's `actor` filter was extended from a single value to a
**comma-separated list of up to 10 distinct identifiers** (`?actor=a1,a2,a3`),
matched OR-within-the-set and AND-combined with every other filter; more than
10 distinct actors (counted after normalization) is rejected with
`422 TOO_MANY_ACTORS`.

This was reconciled across the spec in three steps — `requirements.md` (§1, US1,
US3), then `design.md` (§2.1/§2.2/§3/§3.1/§4.1/§4.3/§4.4/§6/§7), then `tasks.md`
(T1/T3/T4, decisions list, coverage table). The `2026-05-21` spec self-eval
(`../eval-report-2026-05-21-spec-self-eval-2.md`) confirms all three files are
mutually consistent and rates the spec **PASS — proceed to implementation**.

This plan is the **execution plan for the multi-actor slice**. It is a
cross-cutting feature that touches three tasks — **T1** (persistence query),
**T3** (cursor codec), **T4** (API handler + error contract). The existing
per-task plans [`T1-plan.md`](./T1-plan.md), [`T3-plan.md`](./T3-plan.md), and
[`T4-plan.md`](./T4-plan.md) predate the multi-actor decision and still describe
a single-value `actor` (see [`_delta.md`](./_delta.md)); **this plan supersedes
the `actor`-related portions of those three plans.** It can be executed as part
of T1/T3/T4 (the spec now defines `AuditEventQuery` with `List<String> actors`,
so multi-actor is the contract, not an add-on) or, if a single-actor baseline
already shipped, as one follow-on commit applying every step below together.

**Sources.**
- [`../requirements.md`](../requirements.md) — §1 (Overview), US1 (multi-actor
  match, normalization AC, >10 ⇒ `422` AC), US3 (multi-actor pagination AC,
  order/duplicate-independent cursor AC).
- [`../design.md`](../design.md) — §2.1 (`actor` parameter), §2.2 (multi-actor
  filter rule + normalization), §3 / §3.1 (`422 TOO_MANY_ACTORS`), §4.1 (sort
  unaffected), §4.3 (`actor IN (…)` query + sorted actor-set filter hash), §4.4
  (`AuditEventQuery.actors`), §6 (Maximum actors rule + validation precedence),
  §7 (`actor IN` index-backed by `(actor, timestamp, id)`).
- [`../tasks.md`](../tasks.md) — T1/T3/T4 (multi-actor scope, refs, DoD),
  "Decisions baked in", "Requirements / design coverage" table.
- [`../../../AGENTS.md`](../../../AGENTS.md) — hexagonal layering; syntactic
  validation in the API layer; append-only / no read side effects; Spotless.

**Sizing.** One safe commit / PR layered on top of the single-actor baseline:
it compiles, `mvn verify` is green (Spotless + ArchUnit + unit/web-slice/IT), no
schema or data mutation.

## Scope

**In scope:**
- `AuditEventQuery.actor` (`String`) → `actors` (`List<String>`), normalized
  distinct set; persistence `actor IN (…)` predicate.
- `FilterFingerprint` encodes the sorted, de-duplicated actor set so the cursor
  hash is order- and duplicate-independent.
- API handler: parse the comma-separated `actor` parameter, normalize
  (trim / drop-blank / de-duplicate), enforce the 10-actor cap, build `actors`.
- New `422 TOO_MANY_ACTORS` error path in the API error contract.
- Tests across the persistence, cursor, web-slice, and end-to-end IT layers.

**Out of scope:**
- The single-actor baseline mechanics (keyset query, cursor token shape, 90-day
  cap, response DTOs) — owned by T1/T2/T3/T4 as already planned.
- The legacy `?actor=…` handler — unchanged; multi-actor applies only to the
  paginated contract (`design.md` §2.3).
- `design.md` §4.3 cursor-hash delimiter hardening — noted as a minor follow-up
  in `../eval-report-2026-05-21-spec-self-eval-2.md` (actor ids assumed free of
  literal `,`).

## Decisions (from the spec, confirmed during reconciliation)

1. **Match semantics** — OR within the `actor` set, AND with every other filter
   (`design.md` §2.2; SQL `actor IN (…)`).
2. **Normalization** — split on `,`; trim each element; drop empty /
   whitespace-only elements; de-duplicate. An empty resulting set ⇒ no `actor`
   filter (`design.md` §2.2).
3. **Cap** — at most 10 distinct values, counted after normalization; exactly 10
   allowed; `>10` ⇒ `422 TOO_MANY_ACTORS` (`design.md` §3, §6). Fixed constant,
   not configuration.
4. **Cursor hash** — the fingerprint renders the actor set as the distinct
   values **sorted ascending and joined with `,`**, so `f` is independent of
   supplied order and duplicates (`design.md` §4.3).
5. **Validation placement** — the 10-actor cap is an API-layer syntactic check,
   between the `limit` check and the filter-fingerprint step (`design.md` §6
   precedence).

## Step-by-step implementation

### A. Persistence — `actor IN (…)` (extends T1)

1. **`application.port.in.AuditEventQuery`** — change the `actor` component:

   ```java
   public record AuditEventQuery(
       Instant from, Instant to,
       List<String> actors,                 // was: String actor
       String resourceType, String resourceId,
       int limit, KeysetPosition after) {}
   ```

   `actors` is the normalized, distinct set (never `null`; empty ⇒ no `actor`
   filter; `size() <= 10`). Records in `application.port..` stay records
   (`HexagonalArchitectureTest`).

2. **Persistence adapter / `AuditEventSpecifications`** — replace the single
   `actor` equality with a set predicate: when `actors` is non-empty, add
   `root.get("actor").in(query.actors())` (JPA Criteria `in(...)`); when empty,
   add no `actor` predicate. All other filters, the half-open range, the keyset
   predicate, `ORDER BY timestamp ASC, id ASC`, and `LIMIT` are unchanged — the
   `actor IN (…)` term changes only *which* rows match, not their order
   (`design.md` §4.1). `SELECT` only; no schema/data mutation.

### B. Cursor codec — sorted actor-set fingerprint (extends T3)

3. **`api.cursor.FilterFingerprint`** — accept the actor set instead of a single
   string. Canonical encoding for the `actor` line of the hash input
   (`design.md` §4.3):
   - normalize the list: trim each element, drop blank elements, de-duplicate;
   - **sort the distinct values ascending**, join with `,`;
   - render the `actor=<joined>` line in the existing fixed key order
     (`from`, `to`, `actor`, `resourceType`, `resourceId`), `\n`-joined, then
     SHA-256 + base64url (no padding) as today.

   Result: re-ordered or duplicated actor lists yield the **same** `f`, so a
   `nextCursor` stays valid when the same actor set is re-supplied in any order
   (`requirements.md` US3). `encode` / `decode` and `InvalidCursorException` are
   otherwise unchanged.

### C. API handler & error contract — parsing + 10-actor cap (extends T4)

4. **`api.controller.AuditEventController.query`** — bind `actor` as a list and
   normalize:

   ```java
   @RequestParam(name = "actor", required = false) List<String> actor   // Spring splits ?actor=a,b,c
   ...
   List<String> actors = Actors.normalize(actor);   // trim, drop blank, dedup
   if (actors.size() > 10) {
     throw new TooManyActorsException("actor accepts at most 10 distinct values.");
   }
   ```

   `Actors.normalize` is a small package-private helper in `api.controller`
   (or `api.error`): flat-maps any element on `,` (defensive — Spring already
   splits a single param), trims, drops blanks, de-duplicates preserving nothing
   about order, returns an unmodifiable `List<String>`. Run this **after** the
   `limit` check and **before** the fingerprint, per `design.md` §6 precedence.

5. **`FilterFingerprint` / `AuditEventQuery` wiring** — pass `actors` to both:

   ```java
   var fingerprint = new FilterFingerprint(fromTs, toTs, actors, resourceType, resourceId);
   KeysetPosition after = (cursor == null) ? null : cursorCodec.decode(cursor, fingerprint);
   var query = new AuditEventQuery(fromTs, toTs, actors, resourceType, resourceId, limit, after);
   ```

6. **`api.error.TooManyActorsException`** — new plain `RuntimeException` in
   `api.error` (mirrors `InvalidRequestException`, but maps to `422`). A
   dedicated type is used rather than overloading `InvalidRequestException`
   (which is `400`-only).

7. **`api.error.ApiExceptionHandler`** — add a handler mapping
   `TooManyActorsException` ⇒ `422` with body
   `{ "code": "TOO_MANY_ACTORS", "message": …, "status": 422 }` (reuse the
   existing `body(...)` helper). Sits alongside the `InvalidTimeRangeException`
   ⇒ `422` handler; the `400` family is untouched.

8. **Formatting** — `mvn spotless:apply` before committing.

## Test plan

**Persistence IT (`AuditEventPersistenceAdapterIT`)**
- a multi-value `actors` set returns rows whose `actor` equals **any** listed
  value (OR within the set), still AND-combined with a resource filter and the
  time range;
- an empty `actors` list applies no `actor` filter (all in-range rows return);
- `actors` of exactly 10 values is accepted and queried correctly;
- ascending `(timestamp, id)` order and the keyset walk are unchanged with a
  multi-actor filter present.

**Cursor codec unit tests (`CursorCodec` / `FilterFingerprint`)**
- two `actor` lists differing only in value order or in duplicates produce the
  **same** `f` (`a,b` ≡ `b,a` ≡ `b,a,b`);
- a cursor minted for one actor set and decoded against a different actor set
  ⇒ `InvalidCursorException` (hash mismatch);
- `encode` → `decode` round-trips with a multi-actor fingerprint.

**Web-slice test (`AuditEventQueryControllerTest`, `@WebMvcTest`)**
- `?actor=a,b` ⇒ `200`; the captured `AuditEventQuery.actors()` is exactly
  `[a, b]`; whitespace / blank / duplicate entries (`?actor=%20a%20,,a,b`) are
  trimmed and collapsed to `[a, b]`;
- `?actor=` (empty) ⇒ `actors` empty ⇒ no filter;
- exactly 10 distinct actors ⇒ `200`; **11 distinct actors ⇒ `422`** with
  `{ "code": "TOO_MANY_ACTORS", "status": 422 }`;
- the 10-actor cap is checked before the cursor decode (a request with 11
  actors *and* a malformed cursor ⇒ `422 TOO_MANY_ACTORS`, not
  `400 INVALID_CURSOR`).

**End-to-end IT (`AuditEventQueryControllerIT`, Testcontainers)**
- seed events for several actors; `?actor=a,b&from&to` returns exactly the rows
  for `a` and `b`, ascending, body shape per `design.md` §5;
- pagination walk with a multi-actor filter: first page ⇒ non-null
  `nextCursor`; feeding it back returns the next rows with no overlap / no gap;
  the same cursor re-supplied with the actor list **re-ordered** (`b,a`) still
  decodes successfully and continues the walk;
- 11 actors ⇒ `422 TOO_MANY_ACTORS`; error response writes no rows.

## Verification

- `mvn spotless:apply` then `mvn spotless:check` — formatting green.
- `mvn verify` — compiles; **`HexagonalArchitectureTest` green**: the new
  `Actors` helper and `TooManyActorsException` live in `api..`; `AuditEventQuery`
  stays a record in `application.port.in`; no `infrastructure` dependency from
  `api`; the 10-actor cap is a syntactic check in the API layer (no business
  logic added to `api`, none of it leaks into `domain`).
- All persistence, cursor, web-slice, and IT cases above green; all pre-existing
  query-api and legacy `?actor=` / `GET …/{id}` tests still green.
- No Flyway migration — the multi-actor filter reuses the `(actor, timestamp,
  id)` index from `design.md` §7 (T5); `actor IN (…)` is served by per-value
  index scans + bitmap-OR. Schema unchanged.

## Commit

Single commit / PR, e.g.:
`feat: multi-actor filter for GET /api/v1/audit-events (actor list, 10-actor cap)`.

## Dependencies & follow-ups

- **Depends on T1, T3, T4** — the multi-actor steps extend those tasks'
  artifacts (`AuditEventQuery`, `FilterFingerprint` / `CursorCodec`,
  `AuditEventController` / `ApiExceptionHandler`). If T1/T3/T4 are still
  unimplemented, fold these steps into them; if a single-actor baseline already
  merged, apply this plan as the one follow-on commit described above.
- **Follow-up (minor, not blocking):** `design.md` §4.3 should state the
  cursor-hash delimiter assumption (actor ids contain no literal `,`) or adopt
  an unambiguous encoding — see `../eval-report-2026-05-21-spec-self-eval-2.md`.
- The legacy `?actor=…` handler is intentionally left single-actor
  (`design.md` §2.3); no change there.
