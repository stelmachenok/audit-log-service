# T1 — Persistence: keyset query for `audit_events` — Execution Plan

## Context

This is the execution plan for **task T1** of the Query API (`GET /api/v1/audit-events`)
work, decomposed in [`../tasks.md`](../tasks.md). T1 is the **foundation task**: it has no
dependencies, and **T2** (application use case) and **T3** (cursor codec) both build on the
value types and port method it introduces.

```
T1 ──► T2 ──┐
            ├──► T4 ──► (feature complete)
T1 ──► T3 ──┘
T1 ┄┄► T5            (T5 independent; recommended to land after T1)
```

**Goal of T1.** Add the read-side query contract on the output port and implement it in the
persistence adapter using **keyset (seek) pagination**, with a Testcontainers + PostgreSQL
integration test proving the ordering, half-open range, limit, paging stability, filter
combinations, and append-only / no-side-effect guarantees.

**Sources.**
- [`../requirements.md`](../requirements.md) — Overview, User Stories US2 (timeline,
  ascending, deterministic tie-break, half-open range) and US3 (exactly-once pagination under
  concurrent appends).
- [`../design.md`](../design.md) — §1.1 (domain term ↔ column mapping), §4.1 (sort order +
  `id` tie-breaker), §4.2 (why keyset, not offset), §4.3 (query shape with the keyset
  predicate), §6 (half-open range, `limit` bounds), §7 (indexes — informational here, delivered
  in T5), §8 FR1–FR4, §9 NFR, §10 (`AGENTS.md` invariant mapping).
- [`../../../AGENTS.md`](../../../AGENTS.md) — append-only invariant (no `UPDATE`/`DELETE`),
  server-only `timestamp`, mandatory `actor`, read operations have no side effects; hexagonal
  layering rules; Google Java Format via Spotless.

**Sizing.** One safe commit / PR: it compiles, `mvn verify` is green (Spotless + ArchUnit +
unit/integration tests), and it introduces **no schema or data mutation** (no Flyway change,
`spring.jpa.hibernate.ddl-auto` stays `validate`).

**Implementation decision (confirmed).** The dynamic query — half-open `timestamp` range +
optional equality filters + keyset tuple predicate + `ORDER BY timestamp ASC, id ASC` + `LIMIT`
— is built with **JPA Criteria via Spring Data `Specification`s**. `AuditEventJpaRepository`
gains `JpaSpecificationExecutor<AuditEventEntity>`, a small `Specification` builder assembles
the predicates, and paging uses
`PageRequest.of(0, limit, Sort.by("timestamp").ascending().and(Sort.by("id").ascending()))`.

## Scope

**In scope (this task):**
- New value records under `application.port.in`: `KeysetPosition`, `AuditEventQuery`.
- New method on `LoadAuditEventPort`: `List<AuditEvent> find(AuditEventQuery query)`.
- `AuditEventJpaRepository` + a `Specification` builder + `AuditEventPersistenceAdapter.find`.
- Integration test additions in `AuditEventPersistenceAdapterIT`.

**Out of scope (later tasks):**
- `from > to` short-circuit and the 90-day max-window → `422` rule — **T2** (`AuditEventService`).
- Opaque base64url cursor codec + filter-hash — **T3** (API layer).
- REST handler, request validation, DTOs, error contract — **T4**.
- Flyway `V2` composite indexes from `design.md` §7 — **T5**.

**Explicit notes:**
- T1 **trusts `query.limit()`** — `limit` is validated by the caller (T4 today, with the value
  flowing through T2). The adapter does not clamp or re-check it.
- T1 **adds no indexes.** The V1 indexes (`idx_audit_events_timestamp` on `timestamp DESC`,
  `idx_audit_events_actor`) are sufficient for the IT to pass; the optimal composite indexes
  `(timestamp, id)`, `(actor, timestamp, id)`, `(resource_type, timestamp, id)`,
  `(resource_id, timestamp, id)` land in T5.
- Domain vocabulary ↔ column mapping (`design.md` §1.1): API/domain `occurredAt` ⇔ column
  `timestamp` (entity attribute `timestamp`); `actor.id` ⇔ `actor`; `resource.type` ⇔
  `resource_type` (`resourceType`); `resource.id` ⇔ `resource_id` (`resourceId`). `actor.type`
  is **not** modeled in the schema, so it is not a filter.

## Step-by-step implementation

### Step 1 — `KeysetPosition` record

New file
`src/main/java/com/cloudedir/auditlog/application/port/in/KeysetPosition.java`,
package `com.cloudedir.auditlog.application.port.in`:

```java
public record KeysetPosition(Instant occurredAt, UUID id) {
  public KeysetPosition {
    Objects.requireNonNull(occurredAt, "occurredAt is mandatory");
    Objects.requireNonNull(id, "id is mandatory");
  }
}
```

Records are allowed in `application.port..` — `HexagonalArchitectureTest.portsResideInApplicationLayer`
only requires *non-record* classes there to be interfaces.

### Step 2 — `AuditEventQuery` record

New file
`src/main/java/com/cloudedir/auditlog/application/port/in/AuditEventQuery.java`,
same package:

```java
public record AuditEventQuery(
    Instant from,
    Instant to,
    String actor,
    String resourceType,
    String resourceId,
    int limit,
    KeysetPosition after) {
  public AuditEventQuery {
    Objects.requireNonNull(from, "from is mandatory");
    Objects.requireNonNull(to, "to is mandatory");
    // actor / resourceType / resourceId / after are nullable.
    // limit is validated by the caller; a defensive `limit >= 1` check is optional.
  }
}
```

Uses domain wording (`from` / `to` are the half-open bounds on `occurredAt`, i.e. the
`timestamp` column).

### Step 3 — extend `LoadAuditEventPort`

`src/main/java/com/cloudedir/auditlog/application/port/out/LoadAuditEventPort.java` — add:

```java
List<AuditEvent> find(AuditEventQuery query);
```

(Import `com.cloudedir.auditlog.application.port.in.AuditEventQuery`.) Existing `findById` and
`findByActor` are untouched and keep working (per the product decision in `tasks.md`).

### Step 4 — repository support

`src/main/java/com/cloudedir/auditlog/infrastructure/persistence/repository/AuditEventJpaRepository.java`
— additionally extend `JpaSpecificationExecutor<AuditEventEntity>`:

```java
public interface AuditEventJpaRepository
    extends JpaRepository<AuditEventEntity, UUID>, JpaSpecificationExecutor<AuditEventEntity> {
  List<AuditEventEntity> findByActor(String actor);
}
```

This provides `Page<AuditEventEntity> findAll(Specification<AuditEventEntity>, Pageable)`.

### Step 5 — `Specification` builder

New file
`src/main/java/com/cloudedir/auditlog/infrastructure/persistence/repository/AuditEventSpecifications.java`,
package `com.cloudedir.auditlog.infrastructure.persistence.repository`:

```java
public final class AuditEventSpecifications {
  private AuditEventSpecifications() {}

  public static Specification<AuditEventEntity> matching(AuditEventQuery q) {
    return (root, query, cb) -> {
      var predicates = new ArrayList<Predicate>();
      predicates.add(cb.greaterThanOrEqualTo(root.get("timestamp"), q.from())); // half-open: >= from
      predicates.add(cb.lessThan(root.get("timestamp"), q.to()));               // half-open: <  to
      if (q.actor() != null)        predicates.add(cb.equal(root.get("actor"), q.actor()));
      if (q.resourceType() != null) predicates.add(cb.equal(root.get("resourceType"), q.resourceType()));
      if (q.resourceId() != null)   predicates.add(cb.equal(root.get("resourceId"), q.resourceId()));
      var after = q.after();
      if (after != null) {
        // (timestamp, id) > (after.occurredAt, after.id)
        predicates.add(
            cb.or(
                cb.greaterThan(root.get("timestamp"), after.occurredAt()),
                cb.and(
                    cb.equal(root.get("timestamp"), after.occurredAt()),
                    cb.greaterThan(root.get("id"), after.id()))));
      }
      return cb.and(predicates.toArray(Predicate[]::new));
    };
  }
}
```

Entity attribute names exist on `AuditEventEntity`: `timestamp` (`Instant`), `actor`,
`resourceType`, `resourceId`, `id` (`UUID`). This class is in `infrastructure`, so it may use
JPA / Criteria types freely; it depends on `application.port.in.AuditEventQuery`, which is an
allowed direction (ArchUnit forbids `domain`/`application`/`api` → `infrastructure`, not the
reverse).

### Step 6 — implement `find` in the adapter

`src/main/java/com/cloudedir/auditlog/infrastructure/persistence/adapter/AuditEventPersistenceAdapter.java`
— add the override (and the `LoadAuditEventPort.find` is now satisfied):

```java
@Override
public List<AuditEvent> find(AuditEventQuery query) {
  var sort = Sort.by("timestamp").ascending().and(Sort.by("id").ascending());
  var page = PageRequest.of(0, query.limit(), sort);
  return repository.findAll(AuditEventSpecifications.matching(query), page).stream()
      .map(this::toDomain)
      .toList();
}
```

Reuses the existing private `toDomain(AuditEventEntity)`. This issues a `SELECT` only — no
`INSERT`/`UPDATE`/`DELETE`, no triggers. Annotating the read path `@Transactional(readOnly = true)`
is optional and not currently used elsewhere in the adapter.

### Step 7 — formatting

Run `mvn spotless:apply` before committing (Google Java Format, per `AGENTS.md` § Code style).

## Test plan — `AuditEventPersistenceAdapterIT`

Extend the existing IT at
`src/test/java/com/cloudedir/auditlog/infrastructure/AuditEventPersistenceAdapterIT.java`,
reusing its `@SpringBootTest` + `@Testcontainers` (`postgres:16-alpine`) + `@DynamicPropertySource`
setup and the `@Autowired AuditEventPersistenceAdapter adapter`. The `audit_events` table is
shared across tests in the class, so each new test seeds its own data with **unique** actor /
resource values (and/or a disjoint time window) and asserts only against those rows. Keep the
existing `savesAndLoadsEvent` test unchanged.

New `@Test` methods (covering the T1 DoD):

1. **Ascending order with deterministic tie-break.** Insert ≥ 3 rows including two that share
   the same `timestamp` (distinct `id`s); `find` returns them ordered by `(timestamp ASC, id ASC)`,
   and two consecutive `find` calls return the identical sequence. (US2; `design.md` §4.1)
2. **Half-open range.** With a window `[from, to)`: a row with `timestamp == from` **is**
   returned; a row with `timestamp == to` **is not**. (US2; `design.md` §6)
3. **`limit` caps page size.** Insert `n` matching rows, `find` with `limit = k < n` returns
   exactly `k` rows (the first `k` in `(timestamp, id)` order). (`design.md` §6)
4. **Keyset paging — exactly once, no gaps, append-stable (US3; `design.md` §4.2/§4.3, §10).**
   - Page 1: `find(query, after = null, limit = k)`.
   - Page 2: `find(query, after = KeysetPosition(lastRowOfPage1.timestamp(), lastRowOfPage1.id()), limit = k)`.
   - Assert: across pages every matching row appears exactly once, no overlap, no gap; the union
     equals the full sorted result.
   - **Between** the page-1 and page-2 `find` calls, insert additional in-range rows; assert
     page 2 still contains no repeats of page-1 rows and skips nothing that should follow the
     cursor — paging is stable under concurrent appends.
5. **Optional-filter subsets** — each returns exactly the matching rows: (a) `actor` only;
   (b) `resourceType` only; (c) `resourceId` only; (d) `resourceType` + `resourceId`;
   (e) `actor` + a resource filter; (f) none (range only). (US1/US2; `design.md` §2.2, FR1–FR2)
6. **No side effects.** Capture `repository.count()` (autowire `AuditEventJpaRepository`, or
   re-load known rows) before and after a `find`; assert the count and the stored `timestamp`s
   are unchanged — the query writes nothing. (`AGENTS.md` append-only; `design.md` §10, NFR)

## Verification

- `mvn spotless:apply` then `mvn spotless:check` — formatting green.
- `mvn verify` — compiles; **`HexagonalArchitectureTest` green**: the two new records are in
  `application.port.in` and are records (so `portsResideInApplicationLayer` and the
  records-only expectation hold); no forbidden cross-layer dependency is added
  (`infrastructure` → `application.port.in` is allowed). `AuditEventPersistenceAdapterIT`
  (Testcontainers PostgreSQL) green; all pre-existing tests still green.
- Confirm **no Flyway migration** was added or modified and `spring.jpa.hibernate.ddl-auto`
  remains `validate` — T1 changes no schema.

## Commit

Single commit / PR, e.g.: `feat: keyset query port + persistence adapter (T1)`.

## Follow-ups (not part of T1)

- **T2** — `QueryAuditEventUseCase.query(AuditEventQuery)` + `AuditEventService` with the
  `from > to` empty-page short-circuit, the 90-day cap → `InvalidTimeRangeException`, and the
  `AuditEventPage(events, hasMore)` result; `hasMore = events.size() == query.limit()`.
- **T3** — `api.cursor.CursorCodec` (base64url JSON `{v,t,id,f}`), `FilterFingerprint`,
  `InvalidCursorException`.
- **T4** — `GET /api/v1/audit-events` paginated handler (`params = {"from","to"}`), request
  validation + problem-style error contract, response DTOs, `nextCursor` wiring.
- **T5** — Flyway `V2__query_api_indexes.sql`: create the four composite indexes from
  `design.md` §7, drop the superseded `idx_audit_events_actor` / `idx_audit_events_timestamp`.
