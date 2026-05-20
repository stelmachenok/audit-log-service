# Query API — Implementation Tasks (`tasks.md`)

Decomposition of the read-only `GET /api/v1/audit-events` paginated query endpoint
into small, independently shippable units.

- Sources: [`requirements.md`](./requirements.md) (Overview, User Stories US1–US3,
  Out of scope, Open Questions) and [`design.md`](./design.md) (endpoint contract,
  status codes, sorting/pagination, response body, indexes, validation, invariant
  mapping). `design.md` is authoritative where the two overlap.
- Each task is sized to **one safe commit / PR**: it compiles, `mvn verify` is green
  (Spotless + ArchUnit + unit/integration tests), and it introduces no schema or data
  mutation beyond append-only-safe DDL.
- Decisions baked in (confirmed with the product owner):
  - The existing `GET /api/v1/audit-events?actor=…` handler and
    `QueryAuditEventUseCase.findByActor` / `LoadAuditEventPort.findByActor` are **kept**.
    The new paginated handler coexists on the same path (see T4 implementation notes).
  - The §7 Flyway **V2** index migration is in scope (T5).
  - The 90-day max-window → HTTP `422` rule from `design.md` §6 is implemented (T2/T4),
    overriding the still-open "maximum window" question in `requirements.md` §4.

## Conventions

- New value types (`AuditEventQuery`, `KeysetPosition`, `AuditEventPage`) are Java
  `record`s placed under `application.port.in` (allowed by `HexagonalArchitectureTest`,
  which requires everything in `application.port..` to be an interface or a record).
- The opaque cursor codec lives in the **API** layer (never in `domain`), per
  `design.md` §10.
- Run `mvn spotless:apply` before each commit (`AGENTS.md` § Code style).


## Definition of Done

Each task is complete when its implementation compiles and `mvn verify` is green.

---

## T1 — Persistence: keyset query for `audit_events`

**Summary.** Add the read-side query contract on the output port and implement it in the
persistence adapter using keyset (seek) pagination.

- Add records under `application.port.in`:
  - `KeysetPosition(Instant occurredAt, UUID id)`.
  - `AuditEventQuery(Instant from, Instant to, String actor, String resourceType,
    String resourceId, int limit, KeysetPosition after)` — `actor` / `resourceType` /
    `resourceId` / `after` nullable; `limit` already validated by the caller.
- Add to `LoadAuditEventPort`: `List<AuditEvent> find(AuditEventQuery query)`.
- Implement `find` in `AuditEventPersistenceAdapter` (+ repository support as needed):
  half-open range `timestamp >= from AND timestamp < to`; optional equality filters
  combined with `AND`; keyset predicate `(timestamp, id) > (after.occurredAt, after.id)`
  when `after` is present; `ORDER BY timestamp ASC, id ASC`; `LIMIT = query.limit()`.
  `SELECT` only — no `INSERT`/`UPDATE`/`DELETE`, no triggers.

**Dependencies.** None.

---

## T2 — Application: paginated query use case (`from > to` + 90-day cap)

**Summary.** Expose the paginated query on the input port and implement the
application-layer rules.

- Add record `AuditEventPage(List<AuditEvent> events, boolean hasMore)` under
  `application.port.in` (`hasMore` ⇒ a `nextCursor` will be emitted by the API layer).
- Add to `QueryAuditEventUseCase`: `AuditEventPage query(AuditEventQuery query)`.
- Add `domain.exception.InvalidTimeRangeException` (carries the 90-day limit / message).
- Implement `query` in `AuditEventService`:
  - if `from > to` ⇒ return `new AuditEventPage(List.of(), false)` (no port call,
    no error);
  - else if `to − from` > 90 days ⇒ throw `InvalidTimeRangeException`;
  - else delegate to `loadPort.find(query)`, return
    `new AuditEventPage(events, events.size() == query.limit())`.
  - No writes; existing `findById` / `findByActor` behaviour unchanged.

**Dependencies.** T1 (uses `AuditEventQuery`, `KeysetPosition`, `LoadAuditEventPort.find`).

---

## T3 — API: opaque cursor codec

**Summary.** Add a base64url cursor codec used to (de)serialize the keyset boundary,
including a filter-set hash.

- New `api` component, e.g. `api.cursor.CursorCodec`:
  - `encode(KeysetPosition position, FilterFingerprint filters) → String` — base64url of a
    small JSON object `{ "v": 1, "t": <occurredAt ISO-8601>, "id": <uuid>, "f": <hash> }`.
  - `decode(String cursor, FilterFingerprint filters) → KeysetPosition` — base64url-decode,
    parse JSON, require known `v`, require `f` to equal the hash of the supplied filter set;
    otherwise throw `InvalidCursorException` (new API-layer exception).
  - `FilterFingerprint` (or an equivalent helper): canonical ordering + encoding of
    (`from`, `to`, `actor`, `resourceType`, `resourceId`) before hashing, so the hash is
    independent of query-parameter order and of absent-vs-empty distinctions per the chosen
    normalization.
- Pure code: no Spring, JPA, or HTTP types leaking into `domain`.

**Dependencies.** T1 (uses `KeysetPosition`). Can proceed in parallel with T2.

---

## T4 — API: `GET /api/v1/audit-events` paginated handler, DTOs, validation, error contract

**Summary.** Add the paginated request handler alongside the existing actor-only handler,
with full request validation, the response body from `design.md` §5, and the problem-style
error contract from §3 / §3.1.

- New handler on `GET /api/v1/audit-events` discriminated by required query params
  (`@GetMapping(params = {"from", "to"})`), so the existing `findByActor` handler (no
  `params` condition, requires `actor`) still serves `?actor=…` requests and the new
  handler serves `?from=…&to=…[&actor=…&resourceType=…&resourceId=…&cursor=…&limit=…]`.
- Validation (all client-side failures → `400` except the window cap → `422`):
  - `from` / `to` required ⇒ missing ⇒ `400 MISSING_PARAMETER`;
  - `from` / `to` must be ISO-8601 instants with the `Z` offset; any other value or a
    non-`Z` offset ⇒ `400 INVALID_INSTANT`;
  - `limit`: default `50`, integer in `[1, 1000]`; otherwise ⇒ `400 INVALID_LIMIT`;
  - `actor` / `resourceType` / `resourceId`: optional exact-match strings, AND-combined,
    any subset allowed;
  - `cursor`: optional; when present, decode via `CursorCodec` against the request's filter
    fingerprint; undecodable / unknown version / filter-hash mismatch ⇒ `400 INVALID_CURSOR`;
    a valid cursor combined with `from > to` still yields the `200` empty page (T2 rule wins).
- Build `AuditEventQuery` (with `after` from the decoded cursor, if any), call
  `QueryAuditEventUseCase.query`.
- Response (`200`) body per §5: `{ "data": [ { "id", "occurredAt",
  "actor": { "id", "type" }, "resource": { "id", "type" }, "action", "payload" } ],
  "nextCursor": <token|null> }`.
  - New response DTOs (e.g. `AuditEventQueryResponse`, `AuditEventQueryItem` with nested
    `actor` / `resource` objects); mapping in `api.mapper`.
  - Schema mapping (`design.md` §1.1): `occurredAt ← timestamp`, `actor.id ← actor`,
    `actor.type` not modeled ⇒ emitted as `null`, `resource.* ← resource_type/resource_id`,
    `payload ← details` (opaque — passed through unchanged).
  - `nextCursor` = `CursorCodec.encode(lastRowPosition, filterFingerprint)` when
    `page.hasMore()`, else `null`.
- Error handling (`@RestControllerAdvice`, new or extending the existing one):
  - `InvalidTimeRangeException` ⇒ `422` with `{ "code": "INVALID_TIME_RANGE", "message": …,
    "status": 422 }`;
  - the `400` cases above ⇒ `400` with `{ "code": <stable code>, "message": …,
    "status": 400 }` (codes: `MISSING_PARAMETER`, `INVALID_INSTANT`, `INVALID_LIMIT`,
    `INVALID_CURSOR`);
  - error responses mutate no persisted state.
- The endpoint emits no audit event for the query itself (only standard request logging).

**Dependencies.** T2 (use case + `AuditEventPage` + `InvalidTimeRangeException`),
T3 (cursor codec).

---

## T5 — Flyway `V2` migration: composite indexes for the query API

**Summary.** Add the index set from `design.md` §7 and drop the now-redundant `V1`
indexes. DDL only — append-only-safe (no data mutation, no `UPDATE`/`DELETE`).

- New `src/main/resources/db/migration/V2__query_api_indexes.sql`:
  - create (all columns `ASC`): `(timestamp, id)`, `(actor, timestamp, id)`,
    `(resource_type, timestamp, id)`, `(resource_id, timestamp, id)`;
  - drop `idx_audit_events_actor` and `idx_audit_events_timestamp` (superseded).

**Dependencies.** None required to compile; **recommended after T1** so the index set is
validated against the actual query shapes. Can otherwise ship in parallel.

---

## Dependency graph

```
T1 ──► T2 ──┐
            ├──► T4 ──► (feature complete)
T1 ──► T3 ──┘
T1 ┄┄► T5            (T5 independent; recommended to land after T1)
```

Suggested merge order: **T1 → T3 → T2 → T4 → T5** (T3 may swap with T2; T5 may land any
time after T1).

## Requirements / design coverage

| Item | Covered by |
| --- | --- |
| US1 — exact `actor` + `[from, to)`; optional resource narrowing; no-match ⇒ `200` empty; no state change | T1 (filters, range), T2 (empty page, no writes), T4 (`200` body) |
| US2 — full resource timeline in `[from, to)`; ascending; deterministic tie-break; `from`-inclusive / `to`-exclusive | T1 (`ORDER BY timestamp, id`, half-open range, tie-break IT) |
| US3 — exactly-once pagination under concurrent appends; last page ⇒ `nextCursor: null`; malformed cursor ⇒ `400` | T1 (keyset stability IT), T2 (`hasMore`), T3 (codec + hash check), T4 (`nextCursor`, `400 INVALID_CURSOR`, pagination IT) |
| `design.md` §2 endpoint & query params | T4 |
| §3 / §3.1 status codes & error body (`400` family, `422`, `200`-only success) | T2 (`422` source), T4 (validation + advice) |
| §4 sorting, keyset pagination, cursor format & filter hash | T1 (sort + keyset SQL), T3 (cursor encoding/decoding/hash), T4 (cursor wiring) |
| §5 response body & empty-result representation | T4 |
| §6 validation rules & edge cases (`from > to`, 90-day cap, `limit`, half-open, cursor) | T1, T2, T4 |
| §7 indexes for all filter combinations | T5 |
| §8 functional requirements FR1–FR6 | T1 (FR1–FR4), T2 (FR5–FR6), T4 (FR5) |
| §9 / §10 non-functional + `AGENTS.md` invariants (append-only, server-only `timestamp`, mandatory `actor`, no read side effects, architecture) | T1 (SELECT-only IT), T2 (no writes), T4 (no audit event for the query; layering), T5 (DDL-only) |

## Out of scope (per `requirements.md` §3)

Authentication / `401`, authorization / multi-tenant scoping / `403`, full-text search over
`payload`, aggregations/counts, streaming/SSE, range or partial matches on
`actor`/`resourceType`/`resourceId`, descending or alternate sort orders. The
`requirements.md` §4 Open Questions (retention floor, cursor TTL, rate limiting, max
response size, time-zone offsets, per-caller concurrency) are **not** addressed by these
tasks; only the "maximum window" question is resolved (90-day cap, T2/T4).
