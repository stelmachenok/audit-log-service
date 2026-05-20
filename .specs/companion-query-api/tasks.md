# Audit query by actor/resource — tasks

References point to specific anchors in
[requirements.md](./requirements.md) and [design.md](./design.md).

Branch: `feature/audit-query-by-actor-resource` (already cut from `master`).

Implementation status: `T1` through `T9` describe the already-implemented
scalar-actor keyset baseline. Keep them as implementation history. Newly
specified actor-set and cursor-v2 functionality is added as follow-up work in
`T10` through `T13` and must be layered onto the existing endpoint without
reintroducing offset pagination or changing the append-only write model.

## Task graph

```
T1 ──┐
     ├──► T4 ──► T5 ──► T6 ──► T7 ──► T9
T2 ──┤            ▲
     ├──► T3 ─────┘
     └──► T8

Implemented baseline: T1-T9

Actor-set delta:

T10 ──┬──► T11 ──┐
      └──► T12 ──┼──► T13
                 │
                 └──► T9 verification addendum
```

`T1` (additive migration) and `T2` (new types) have no dependencies and can
land in either order. `T9` (perf verification) is the last gate before the
feature merges to `master`. For the actor-set delta, `T10` is the Application
model prerequisite, `T11` updates cursor compatibility, `T12` updates the
repository query path, and `T13` is the API/test/performance sign-off.

---

## T1 — Flyway V3: add new keyset indexes (additive only)

**Goal.** Introduce the two composite indexes that make keyset pagination
index-bounded. **No drops** in this PR.

**References.**
- requirements.md → AC-3.2 (queries must run through the new keyset
  composite indexes, verified by EXPLAIN ANALYZE).
- design.md → *Indexes → New indexes (Flyway `V3__add_keyset_indexes.sql`)*.

**Scope.**
- New file `src/main/resources/db/migration/V3__add_keyset_indexes.sql`
  with plain `CREATE INDEX` for `idx_audit_events_actor_ts_id` and
  `idx_audit_events_resource_ts_id`.
- Update the existing `AuditEventPersistenceIntegrationTest` only if the
  Testcontainers bootstrap fails on the new migration; otherwise leave it.

**Definition of done.**
- `./gradlew flywayMigrate` (or the equivalent integration test) applies
  V3 against a fresh PG 16 Testcontainer with no errors.
- `\d+ audit_events` (verified in the integration test or one-off check)
  shows both new indexes alongside the existing three.
- All existing tests pass — `unitTest`, `integrationTest`, `archUnitTest`.

**Dependencies.** None. Can ship before any code change.

---

## T2 — Application value types: `AuditEventCursor`, `AuditEventPage`, `AuditEventQuery`

**Goal.** Add the new Application-layer types as pure records, not yet
wired into any caller.

**References.**
- requirements.md → AC-2.1 (envelope shape), AC-2.8 (opaque cursor),
  AC-5.1 (cursor + request types in Application).
- design.md → *Cursor format*, *API contract → Response*,
  *Integration with arch layers → Application layer (Records / value types)*.

**Scope.**
- `com.auditlog.application.AuditEventCursor` — record `(Instant ts, UUID id,
  String actor, String resource, Instant from, Instant to, int v)` with
  `encode()` / `decode(String)` using `Base64.getUrlEncoder().withoutPadding()`
  over a Jackson-serialised JSON envelope. Version constant `1`.
- `com.auditlog.application.AuditEventPage` — record
  `(List<AuditEventView> items, String nextCursor, boolean hasMore)`.
- `com.auditlog.application.AuditEventQuery` — record carrying `actor?`,
  `resource?`, `from`, `to`, `limit`, `cursor?`. Ctor does no validation
  yet (T3 owns validation).
- Unit test `AuditEventCursorTest` — encode/decode round-trip, malformed
  base64 → exception, version mismatch → exception.

**Definition of done.**
- New types compile under `com.auditlog.application`.
- `AuditEventCursorTest` passes.
- No production code references the new types yet (grep confirms).
- Existing tests untouched and continue to pass.

**Dependencies.** None.

---

## T3 — Cross-field validation for `AuditEventQuery`

**Goal.** Implement every rule in the design's *Validation rules* table on
`AuditEventQuery`, returning a typed validation error.

**References.**
- requirements.md → AC-1.3, AC-1.4, AC-1.5, AC-1.6, AC-1.7, AC-2.4, AC-2.5,
  AC-2.6, AC-2.7.
- design.md → *Validation rules* (table).

**Scope.**
- Introduce `com.auditlog.application.ValidationError` (record with
  `code`, `message`, `field?`).
- Introduce `com.auditlog.application.ValidationException`
  (`RuntimeException` carrying a `ValidationError`).
- Add `AuditEventQuery.validate()` returning the same query on success and
  throwing `ValidationException` on failure. Missing `from`/`to` validation
  reports the first missing parameter encountered.
- Unit test `AuditEventQueryValidationTest` — one test per row of the
  validation rules table, plus the cursor/filter mutual-exclusion case.

**Definition of done.**
- Every row of the *Validation rules* table is covered by a passing unit
  test asserting the correct error code and field.
- `AuditEventQuery.validate()` is referenced by no production caller yet
  (added in T5).

**Dependencies.** T2 (uses `AuditEventQuery`, `AuditEventCursor`).

---

## T4 — Repository port: add `findPage(AuditEventQuery, Instant, UUID)` and JPA implementation

**Goal.** Extend the `AuditEventRepository` port with a keyset-pagination
method and implement it in the existing infrastructure adapter, alongside
the current `find(AuditEventSearchCriteria)` (which stays for now).

**References.**
- requirements.md → AC-1.1 (ordering), AC-1.2 (AND filter), AC-1.8
  (`[from, to)` semantics), AC-2.3 (cursor compare), AC-3.2 (index usage),
  AC-4.1, AC-4.2, AC-4.3 (response shape, hot table only), AC-5.2
  (JPA isolated to Infrastructure).
- design.md → *Pagination strategy → Continuation predicate*, *Sort &
  determinism*, *Integration with arch layers → Infrastructure layer*,
  *Indexes → New indexes*.

**Scope.**
- Add `AuditEventPage findPage(AuditEventQuery query, Instant cursorTs,
  UUID cursorId)` to
  `com.auditlog.application.AuditEventRepository`.
- Implement in `com.auditlog.infrastructure.persistence.JpaAuditEventRepository`
  using a native query that matches the SQL in design.md *Continuation
  predicate*, with `LIMIT :limit + 1` and slicing logic in the adapter
  (NOT in the service — the adapter knows it fetched `limit+1`).
- The adapter MUST select **only** from `audit_events` (AC-4.3); never
  `audit_events_archive`.
- Map results to `AuditEventView` using the existing entity-to-view mapping.
- Annotate with `@Transactional(readOnly = true)`.
- Integration test `AuditEventQueryRepositoryIntegrationTest`
  (Testcontainers) — seed ≥ 250 rows, fetch 3+ pages with limit=100, assert
  no duplicate ids, assert ordering `(timestamp desc, id desc)`,
  interleave inserts at the head between pages and assert they are not
  visited.
- Add `EXPLAIN (ANALYZE, BUFFERS)` assertion (or capture into a log) showing
  one of the new V3 indexes is used at unit-of-test scale. T9 owns the
  50M-row AC-3.2 verification.

**Definition of done.**
- `JpaAuditEventRepository.findPage` exists and the new integration test
  passes.
- Old `find(...)` and the existing
  `AuditEventPersistenceIntegrationTest` are unchanged.
- `archUnitTest` remains green (no new package, no new framework leak).

**Dependencies.** T1 (indexes must exist for the SQL to use them),
T2 (uses `AuditEventQuery`, `AuditEventPage`).

---

## T5 — Application service: `AuditEventQueryService.queryPage(...)`

**Goal.** Add a new orchestration method on the existing
`AuditEventQueryService` that validates, decodes cursors, and calls
`findPage`. Old `query(AuditEventSearchCriteria)` stays.

**References.**
- requirements.md → AC-2.1, AC-2.2, AC-2.3, AC-2.4, AC-2.5, AC-2.6,
  AC-2.7, AC-2.8, AC-3.3 (side-effect free), AC-5.1.
- design.md → *Cursor format*, *Pagination strategy → Page assembly*,
  *Integration with arch layers → Application layer*.

**Scope.**
- New method `AuditEventPage queryPage(AuditEventQuery query)` on
  `AuditEventQueryService`:
  1. Validate `limit` for both first-page and cursor requests.
  2. If `query.cursor()` is non-null: reject any client filter param
     (AC-2.4), decode the cursor, validate the decoded envelope contains a
     bounded pinned filter/window, and reapply the encoded filters.
  3. Otherwise: call `query.validate()` (T3); reject on error.
  4. Call `repository.findPage(query, cursorTs, cursorId)` and return the
     adapter's `AuditEventPage` unchanged.
- Unit test `AuditEventQueryServiceTest` (Mockito or hand-rolled fake of
  the port) — exercise: happy path single page, happy path multi-page,
  cursor + filter conflict, malformed cursor, cursor limit out of range,
  invalid decoded cursor envelope, validation failure.
- `AuditEventQueryService` and the new method must contain **no**
  Spring/JPA/HTTP imports (verify in CR).

**Definition of done.**
- New service method passes its unit tests.
- No call site uses the new method yet (added in T6).
- ArchUnit + existing tests continue to pass.

**Dependencies.** T2, T3, T4.

---

## T6 — API layer: switch `GET /audit-events` to keyset, add `@RestControllerAdvice`

**Goal.** Refactor `AuditEventController` to consume the new service
method, accept the new query parameters, and return the new envelope.
This is the user-visible breaking change.

**References.**
- requirements.md → AC-1.1, AC-1.3, AC-1.4, AC-1.5, AC-1.6, AC-1.7, AC-2.4,
  AC-2.5, AC-2.6, AC-2.7, AC-4.1, AC-4.2, AC-5.1; *Out of scope* item
  "Backwards-compatibility shim for the current offset/limit pagination".
- design.md → *API contract* (all subsections), *Errors — 400*,
  *Integration with arch layers → API layer*.

**Scope.**
- Replace the body of `GET /audit-events` to:
  - Read `actor`, `resource`, `from`, `to`, `limit`, and `cursor` from
    query parameters directly and default `limit` to `100`.
  - Translate those values to `AuditEventQuery` (Application).
  - Call `AuditEventQueryService.queryPage(...)`.
  - Map `AuditEventPage` → `AuditEventPageResponse` (API DTO with
    `items`, `nextCursor`, `hasMore`).
- Drop the existing `limit`/`offset` parameters from the request DTO.
  Add `cursor`. Keep `actor`, `resource`, `from`, `to`, `limit` per the
  contract.
- Add `com.auditlog.api.GlobalExceptionHandler`
  (`@RestControllerAdvice`) mapping `ValidationException` and timestamp
  binding failures to the
  `{ error, message, field? }` body shape from design.md.
- Spring MVC slice test (`@WebMvcTest`) — one assertion per error path
  in the validation table, plus the happy path returning the envelope.
- Update the existing controller integration test (full Spring context +
  Testcontainers) so the golden path uses cursor pagination.

**Definition of done.**
- `./gradlew unitTest integrationTest archUnitTest` all green.
- Manual `curl` against `bootRun` returns the new envelope shape on the
  happy path and the new error shape on each validation failure.
- The endpoint no longer binds `offset`; supplying `offset` is silently
  ignored as an unknown query parameter.

**Dependencies.** T5.

---

## T7 — Cleanup: remove dead code and Flyway V4 drop old indexes

**Goal.** Now that nothing references the old query path, remove it.
This is a destructive PR, intentionally separate from T6 so a regression
in T6 does not require also reverting a schema drop.

**References.**
- requirements.md → *Out of scope* item "Backwards-compatibility shim for
  the current offset/limit pagination".
- design.md → *Indexes → Indexes to drop*, *Integration with arch layers →
  Type changes summary*.

**Scope.**
- New file `src/main/resources/db/migration/V4__drop_legacy_indexes.sql`
  with `DROP INDEX idx_audit_events_actor_timestamp;`
  and `DROP INDEX idx_audit_events_resource_timestamp;`.
- Delete `com.auditlog.application.AuditEventSearchCriteria`.
- Delete `AuditEventRepository.find(AuditEventSearchCriteria)` and its
  JPA implementation.
- Delete `AuditEventQueryService.query(AuditEventSearchCriteria)` (the
  old method that wraps `find`).
- Delete or rewrite any test that references those types
  (`AuditEventPersistenceIntegrationTest` will need its old `find` paths
  removed — keep only what remains meaningful for the write path).

**Definition of done.**
- `grep -rn AuditEventSearchCriteria src/` returns nothing.
- V4 applies cleanly on a fresh PG 16 Testcontainer; only the three
  expected indexes remain (`idx_audit_events_actor_ts_id`,
  `idx_audit_events_resource_ts_id`, `idx_audit_events_timestamp`) plus
  `audit_events_pkey`.
- All test tasks green.

**Dependencies.** T6 (must merge first so the old code has zero callers).

---

## T8 — ArchUnit assertion: query value types stay in Application

**Goal.** Lock in AC-5.1 and AC-5.4 with static checks so future refactors
cannot push query value types or cursor encoding into the wrong layer.

**References.**
- requirements.md → AC-5.1, AC-5.4.
- design.md → *Test coverage map → ArchUnit*,
  *Alignment with AGENTS.md → Architecture enforcement*.

**Scope.**
- Add to `ArchitectureRulesTest`:
  - `noClasses().that().resideInAPackage("..api..").should().dependOnClassesThat().haveSimpleName("AuditEventCursor")`
    — the API layer must not import `AuditEventCursor` directly; it sees
    only the opaque `String nextCursor`.
  - `classes().that().haveSimpleName("AuditEventCursor").should().resideInAPackage("..application..")`.
  - Equivalent package assertions for `AuditEventQuery` and `AuditEventPage`.
- Verify the rules fail when intentionally violated (manual one-off
  experiment in a scratch branch — do not commit the violating change).

**Definition of done.**
- New assertions pass on the current code.
- `archUnitTest` task green.

**Dependencies.** T2 (cursor type must exist).

---

## T9 — Performance verification against 50M rows

**Goal.** Prove AC-3.1 and the 50M-row portion of AC-3.2 before the feature
merges to `master`.

**References.**
- requirements.md → AC-3.1, AC-3.2.
- design.md → *Indexes → T9 performance verification*.

**Scope.**
- Generate or load a synthetic 50M-row `audit_events` dataset with realistic
  actor/resource distributions and enough timestamp spread to exercise
  bounded windows.
- Run `EXPLAIN (ANALYZE, BUFFERS)` for actor-only, resource-only, and
  combined actor/resource compliant requests. The plan must use
  `idx_audit_events_actor_ts_id` or `idx_audit_events_resource_ts_id` and
  must select only from `audit_events`.
- Measure server-side p95 latency for representative first-page and
  cursor-page requests at `limit=100` and `limit=500`.
- Capture the commands, dataset assumptions, EXPLAIN excerpts, latency
  summary, and pass/fail result in a short verification artifact under
  `.specs/query-api/`.

**Definition of done.**
- p95 latency is ≤ 300ms for every measured compliant request shape.
- EXPLAIN ANALYZE verifies the new keyset indexes on the synthetic 50M-row
  dataset.
- The verification artifact is reviewed before merging the feature branch to
  `master`.

**Dependencies.** T7 (final query path and index set must be in place).

---

## T10 — Application model: canonical actor sets

**Goal.** Add the current requirements' multi-actor semantics to the existing
Application-layer query model while preserving single-actor behavior as a
one-item actor set.

**References.**
- requirements.md → AC-1.1, AC-1.2, AC-1.9, AC-1.10, AC-1.11, AC-1.12,
  AC-5.1.
- design.md → *API contract → Request — first page (no cursor)*,
  *Validation rules*, *Type changes summary*.

**Scope.**
- Replace scalar `AuditEventQuery.actor` usage with a canonical actor set
  (`List<String>` or a small Application-layer value type). The API still
  receives the existing `actor` query parameter string.
- Add deterministic parser logic in the Application layer:
  split on comma, trim tokens, reject empty tokens, deduplicate, sort
  lexicographically, and enforce 1-10 actor values.
- Treat no actor filter as absent, a single actor as a one-item set, and
  actor + resource as a logical AND over `actor IN set` and exact `resource`.
- Keep validation side-effect free and framework-free; do not add Spring,
  JPA, or API dependencies to Application types.
- Add unit tests for one actor, two to ten actors, trim/dedupe/sort, empty
  tokens, blank actor, over-10 actors, missing actor/resource, and actor +
  resource AND semantics at the query model boundary.

**Definition of done.**
- `AuditEventQuery` exposes canonical actors to Application callers and no
  production code depends on comma-splitting outside the Application layer.
- Single-actor requests continue to validate and produce the same effective
  filter as before, represented as a one-item actor set.
- Invalid actor sets fail with `INVALID_ACTOR_SET`; over-10 actors produce a
  message that states the 10-actor cap.
- `unitTest` and `archUnitTest` pass.

**Dependencies.** T1-T9 implemented baseline.

---

## T11 — Cursor v2: pin full actor sets

**Goal.** Upgrade the cursor envelope from scalar actor version `1` to actor
set version `2`, and reject unsupported old cursor shapes.

**References.**
- requirements.md → AC-2.3, AC-2.5, AC-2.8, AC-2.9, AC-5.1.
- design.md → *Cursor format*, *Validation rules*,
  *Type changes summary*.

**Scope.**
- Change `AuditEventCursor` to encode nullable `actors` instead of scalar
  `actor`, using the same canonical actor-set representation as T10.
- Set the supported cursor version constant to `2`; any cursor with an
  unsupported version, including version `1`, returns `INVALID_CURSOR`.
- Validate decoded cursors before repository access: anchor timestamp/id
  present, at least one pinned actor set or resource, valid `[from, to)`
  window no wider than seven days, and canonical actors when present.
- Keep cursors unsigned, non-expiring, opaque, and URL-safe as specified.
- Add cursor unit tests for no actors + resource, one actor, multiple actors,
  malformed input, non-JSON input, version mismatch, missing pinned filters,
  invalid time windows, empty actor tokens, over-10 actors, and non-canonical
  actor order/duplicates.

**Definition of done.**
- New cursors encode `actors` arrays and never encode scalar `actor`.
- Cursor requests reapply only the filters pinned inside the decoded cursor;
  client-supplied `actor`, `resource`, `from`, or `to` alongside `cursor`
  still fails with `CONFLICTING_PARAMETERS`.
- Existing scalar-actor version-1 cursors are rejected as unsupported rather
  than silently interpreted.
- `unitTest` and `archUnitTest` pass.

**Dependencies.** T10.

---

## T12 — Infrastructure: multi-actor keyset query path

**Goal.** Add index-backed multi-actor query support to the existing
`findPage` implementation without changing response ordering, hot-table-only
reads, or keyset pagination semantics.

**References.**
- requirements.md → AC-1.1, AC-1.2, AC-1.8, AC-2.3, AC-3.2, AC-3.3,
  AC-3.4, AC-4.3, AC-5.2.
- design.md → *Pagination strategy → Continuation predicate*,
  *Indexes → New indexes*, *Combined actor-set + resource queries*.

**Scope.**
- Update `AuditEventRepository.findPage(...)` callers and the JPA adapter to
  accept the canonical actor set from `AuditEventQuery`.
- Replace scalar `actor = :actor` filtering with a PostgreSQL array-backed
  predicate equivalent to `actor = ANY(:actors)` when actors are present.
- Preserve `resource` as an AND filter, `[from, to)` bounds, keyset
  continuation over `(event_timestamp DESC, id DESC)`, `LIMIT limit + 1`,
  selection only from `audit_events`, and cursor creation from the last kept
  row.
- Ensure generated `nextCursor` contains the full canonical actor set, not
  only the actor value from the last row.
- Do not add a combined `(actor, resource, event_timestamp, id)` index in this
  task. If later 50M-row verification shows the existing actor index is not
  sufficient, capture that as a follow-up spec/task instead of guessing.
- Add Testcontainers coverage for actor-only multi-actor paging, multi-actor
  + resource paging, exact case-sensitive matching, deterministic order across
  actors, no duplicate ids, no skipped baseline rows, and concurrent inserts
  at the head between cursor pages.

**Definition of done.**
- Multi-actor first-page and cursor-page queries return globally ordered
  results across all selected actors.
- Resource-only and single-actor queries keep their existing behavior.
- Integration tests prove the adapter uses the hot table only and emits
  cursors that preserve the full actor set.
- `unitTest`, `integrationTest`, and `archUnitTest` pass, or any local
  Testcontainers/Docker limitation is recorded clearly.

**Dependencies.** T10, T11.

---

## T13 — API, docs, and actor-set verification

**Goal.** Expose the actor-set behavior through the existing `GET
/audit-events` endpoint, update project docs required by AGENTS.md, and
extend performance sign-off for the new compliant request shapes.

**References.**
- requirements.md → AC-1.1 through AC-1.12, AC-2.1 through AC-2.9,
  AC-3.1 through AC-3.4, AC-4.1 through AC-4.3, AC-5.4.
- design.md → *API contract*, *Errors — 400*,
  *T9 performance verification*, *Test coverage map*.

**Scope.**
- Keep the public endpoint path and query parameter names unchanged:
  `GET /audit-events?actor=a,b&resource=...&from=...&to=...&limit=...`.
- Ensure the API layer only translates HTTP parameters to Application DTOs;
  actor parsing, cursor decoding, and validation remain outside the
  controller.
- Add/extend MVC and full-context integration tests for comma-separated
  actors, actor/resource AND filtering, invalid actor-set errors, cursor +
  filter conflict, cursor-v2 continuation, and ignored legacy `offset`.
- Update `README.md` API documentation for comma-separated actors, actor-set
  limits, cursor-v2 opacity, response envelope, and relevant validation
  errors. No startup/config documentation changes are needed unless the
  implementation introduces them.
- Append progress to `NOTES.md` when this actor-set implementation lands.
- Extend the T9 verification artifact with actor-only multi-actor and
  actor-set + resource `EXPLAIN (ANALYZE, BUFFERS)` runs on the synthetic
  50M-row dataset, plus p95 measurements for `limit=100` and `limit=500`.

**Definition of done.**
- The existing endpoint returns the specified envelope for single-actor,
  multi-actor, resource-only, and actor-set + resource queries.
- Error responses use the existing
  `{ "error": "...", "message": "...", "field": "..." }` shape.
- `README.md` reflects the new API behavior and no stale scalar-only cursor
  examples remain in project docs.
- The performance artifact shows the new V3 keyset indexes are used for
  single-actor, multi-actor, resource-only, and actor-set + resource shapes,
  or records an explicit follow-up indexing task if the existing plan fails.
- `./gradlew unitTest integrationTest archUnitTest` is green, or unavailable
  Docker/Testcontainers verification is called out in the task notes.

**Dependencies.** T11, T12.

---

## Suggested PR order and rollback notes

| # | Task | Reversible by |
|---|------|---------------|
| 1 | T1   | `DROP INDEX` of the two new indexes (no schema-shape change). |
| 2 | T2   | `git revert` — pure additive code. |
| 3 | T3   | `git revert` — adds tests + a method on an unused record. |
| 4 | T8   | `git revert` — single ArchUnit rule. |
| 5 | T4   | `git revert` — port method and adapter method are additive. |
| 6 | T5   | `git revert` — service method is additive. |
| 7 | T6   | `git revert` — controller swap; same revert restores the old offset endpoint. |
| 8 | T7   | `git revert` of the code change + Flyway `V5__restore_legacy_indexes.sql` if the drop has already shipped. |
| 9 | T9   | No production rollback — verification artifact only. |
| 10 | T10 | `git revert` — Application-level actor-set model and validation only. |
| 11 | T11 | `git revert` — cursor-v2 code and tests; old implementation remains in git history only. |
| 12 | T12 | `git revert` — repository query-path update; no migration rollback expected. |
| 13 | T13 | `git revert` for API/docs/test edits; verification artifact has no production rollback. |

T8 is slotted before T4–T7 because it's cheap, independent, and protects
the layer boundary the moment the cursor type exists.

For the actor-set delta, T10-T13 should land after the implemented baseline.
Avoid mixing a future index experiment into T12/T13 unless the 50M-row
verification proves it is necessary; schema changes must go through a new
Flyway migration and a separate task.
