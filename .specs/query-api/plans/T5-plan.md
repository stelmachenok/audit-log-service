# T5 — Flyway `V2` migration: composite indexes for the query API — Execution Plan

## Context

This is the execution plan for **task T5** of the Query API work, decomposed in
[`../tasks.md`](../tasks.md). T5 is **independent** of the code tasks — it only adds a Flyway
migration with the index set the read path needs and drops the now-redundant `V1` indexes:

```
T1 ──► T2 ──┐
            ├──► T4 ──► (feature complete)
T1 ──► T3 ──┘
T1 ┄┄► T5            (T5 independent; recommended to land after T1)
```

**Goal of T5.** Deliver the index set from `design.md` §7 as `V2__query_api_indexes.sql`:
every supported query (the always-present `timestamp` half-open range, optionally narrowed by
exact-match `actor` / `resource_type` / `resource_id`, ordered and keyset-paginated on
`(timestamp, id)`) is satisfied by **one composite index** — the equality-filtered column (or,
for the range-only case, the range column) leads, and `(timestamp, id)` trails, so the range
scan and the sort/keyset boundary are served with no extra sort node. The two `V1` indexes —
`idx_audit_events_actor` and `idx_audit_events_timestamp` (`timestamp DESC`) — become redundant
(`(actor, timestamp, id)` supersedes the first; `(timestamp, id)` ascending matches this
endpoint's sort direction better than the descending one) and are dropped in the same migration.
**DDL only** — no `INSERT`/`UPDATE`/`DELETE`, no triggers — so it is consistent with the
append-only invariant.

**Sources.**
- [`../design.md`](../design.md) — §7 (the proposed index set + why it covers every supported
  filter combination; the `V1` indexes become redundant and are dropped), §9 (queries must
  remain index-backed under all supported filter combinations), §10 (the migration is DDL-only,
  no data mutation ⇒ consistent with the append-only invariant), §4.1/§4.2 (sort + keyset are on
  `(timestamp, id)` ascending), §1.1 (domain term ↔ column mapping).
- [`../requirements.md`](../requirements.md) — US2 (resource timeline, ascending), US3
  (exactly-once keyset pagination), §4 (NFR — performance / index-backed reads).
- [`../../../AGENTS.md`](../../../AGENTS.md) — schema changes only via Flyway (no auto-DDL;
  `spring.jpa.hibernate.ddl-auto=validate`); append-only invariant (no `UPDATE`/`DELETE` of
  data — index DDL is fine); integration tests use Testcontainers + PostgreSQL.
- [`./T1-plan.md`](./T1-plan.md), [`./T4-plan.md`](./T4-plan.md) — define the actual query
  shapes (`AuditEventSpecifications.matching` / the `GET` handler) that this index set serves.

**Sizing.** One safe commit / PR: it compiles, `mvn verify` is green (Spotless + ArchUnit +
all ITs incl. the migrated schema), no data mutation.

## Scope

**In scope (this task):**
- New `src/main/resources/db/migration/V2__query_api_indexes.sql` (DDL only): create the four
  composite indexes; drop the two superseded `V1` indexes.
- A small integration test asserting the resulting index set on the migrated schema.

**Out of scope:**
- Any Java/code change — the persistence query (T1), the use case (T2), the cursor codec (T3),
  and the REST handler (T4) are unaffected by this migration (they query by column, not by index
  name). No entity / repository / DTO changes.
- Tuning beyond `design.md` §7 (e.g. partial indexes, covering `INCLUDE` columns, `CONCURRENTLY`
  rollout) — see the operational note below.

**Explicit notes:**
- **Index naming** follows the `V1` convention `idx_audit_events_<columns>`:
  `idx_audit_events_timestamp_id`, `idx_audit_events_actor_timestamp_id`,
  `idx_audit_events_resource_type_timestamp_id`, `idx_audit_events_resource_id_timestamp_id`.
- All index columns are **ascending** — matching `ORDER BY timestamp ASC, id ASC` and the keyset
  predicate `(timestamp, id) > (?, ?)` (`design.md` §4.1). `timestamp` is left unquoted, as in
  `V1` (PostgreSQL accepts it as a column identifier there).
- **Plain `CREATE INDEX`** (not `CONCURRENTLY`): Flyway wraps each migration in a transaction by
  default and `CREATE INDEX CONCURRENTLY` cannot run inside one; for a fresh or small table it is
  also unnecessary. On a large pre-populated production table, switch this migration to a
  non-transactional Flyway script and use `CONCURRENTLY` — recorded as an operational note, not
  done here.
- **Append-only / no side effects:** the script contains only `CREATE INDEX` / `DROP INDEX` — no
  `INSERT`/`UPDATE`/`DELETE`, no triggers (`design.md` §10).
- `spring.jpa.hibernate.ddl-auto` stays `validate` — Hibernate schema validation checks tables
  and columns, not indexes, so adding/dropping indexes does not affect it.
- The `(resource_id, timestamp, id)` index is also the most selective single index when both
  `resourceType` and `resourceId` are supplied (`design.md` §7); multi-filter combinations stay
  index-backed via the most selective single index plus cheap residual equality filters (or a
  bitmap-AND of two of these indexes) — no schema change needed beyond this set.

## Step-by-step implementation

### Step 1 — `V2__query_api_indexes.sql`

New file `src/main/resources/db/migration/V2__query_api_indexes.sql`:

```sql
-- Query API (design.md §7): one composite index per supported filter combination,
-- each trailing in (timestamp, id) so the half-open range scan and the
-- ORDER BY timestamp ASC, id ASC / keyset boundary need no extra sort node.

CREATE INDEX idx_audit_events_timestamp_id
    ON audit_events (timestamp, id);

CREATE INDEX idx_audit_events_actor_timestamp_id
    ON audit_events (actor, timestamp, id);

CREATE INDEX idx_audit_events_resource_type_timestamp_id
    ON audit_events (resource_type, timestamp, id);

CREATE INDEX idx_audit_events_resource_id_timestamp_id
    ON audit_events (resource_id, timestamp, id);

-- V1 indexes superseded by the above:
--   idx_audit_events_actor      → covered by idx_audit_events_actor_timestamp_id
--   idx_audit_events_timestamp  → (timestamp DESC) replaced by ascending idx_audit_events_timestamp_id
DROP INDEX idx_audit_events_actor;
DROP INDEX idx_audit_events_timestamp;
```

(Order: create the new indexes first, then drop the old ones — within Flyway's single
transaction the order is immaterial, but it reads as a clean hand-off.)

### Step 2 — formatting / sanity

No Java changed, so Spotless is a no-op, but still run `mvn spotless:check` as part of `verify`.
Confirm there is exactly one `V2__*.sql` and the version prefix is `V2` (Flyway will fail fast on
a version gap or duplicate).

## Test plan — `AuditEventSchemaIT` (or extend an existing IT)

New file `src/test/java/com/cloudedir/auditlog/infrastructure/AuditEventSchemaIT.java`
(or add a `@Test` to `AuditEventPersistenceAdapterIT`). `@SpringBootTest` + `@Testcontainers`
(`postgres:16-alpine`) + `@DynamicPropertySource` — the same pattern as the existing ITs; Flyway
runs `V1` then `V2` on startup. Use `@Autowired JdbcTemplate` (Spring Boot auto-configures it —
`spring-jdbc` is on the classpath via `spring-boot-starter-data-jpa`).

- **Migration applies cleanly.** The context starts (which means `V1`+`V2` migrated without
  error); optionally assert `flyway_schema_history` has a row for version `2`, `success = true`.
- **Index set is exactly as expected.**
  `jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE tablename = 'audit_events'", String.class)`
  contains (besides the primary-key index `audit_events_pkey`):
  `idx_audit_events_timestamp_id`, `idx_audit_events_actor_timestamp_id`,
  `idx_audit_events_resource_type_timestamp_id`, `idx_audit_events_resource_id_timestamp_id`;
  and does **not** contain `idx_audit_events_actor` or `idx_audit_events_timestamp`.
- (Optional, nice-to-have, not required by the DoD) **index-backed smoke check:** insert a handful
  of rows and `EXPLAIN` a representative query (range only; `actor` + range; `resource_id` + range)
  to assert an `Index Scan` / `Bitmap Index Scan` on one of the new indexes rather than a `Seq Scan`.
  Keep it lenient (planner choices vary with tiny row counts) or skip if it proves flaky.
- **No data mutation by the migration:** the migration script is DDL-only; (optionally) assert the
  pre-existing ITs' row expectations are unaffected — covered transitively by the rest of the suite.
- All pre-existing ITs (`AuditEventPersistenceAdapterIT`, `AuditEventControllerIT`, plus the T1/T4
  query ITs) stay green against the migrated schema.

## Verification

- `mvn spotless:check` — green (no Java changes).
- `mvn verify` — Flyway migrates `V1`+`V2` on a fresh Testcontainers PostgreSQL; `AuditEventSchemaIT`
  confirms the four new indexes exist and the two old ones are gone; **all pre-existing tests and the
  T1/T4 query ITs stay green**; `HexagonalArchitectureTest` green (no code change). The migration
  contains no `INSERT`/`UPDATE`/`DELETE` and no triggers.
- `spring.jpa.hibernate.ddl-auto` remains `validate`; no entity or repository change.

## Commit

Single commit / PR, e.g.: `feat: Flyway V2 — composite indexes for the query API, drop superseded V1 indexes (T5)`.

## Dependencies & follow-ups

- **No build dependency** on T1–T4. **Recommended to land after T1** so the index columns are
  validated against the actual query shapes; otherwise it may ship in parallel (suggested overall
  merge order from `tasks.md`: T1 → T3 → T2 → T4 → T5).
- Operational follow-up (not part of this task): if/when `audit_events` is large in production,
  re-issue this index creation as a non-transactional Flyway migration using
  `CREATE INDEX CONCURRENTLY` (and `DROP INDEX CONCURRENTLY`) to avoid a write-blocking lock.
- This completes the Query API spec set; the remaining `requirements.md` §4 Open Questions
  (retention floor, cursor TTL, rate limiting, max response size, time-zone offsets, per-caller
  concurrency) are intentionally untouched by T1–T5.
