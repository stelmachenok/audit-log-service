# T2 — Application: paginated query use case (`from > to` + 90-day cap) — Execution Plan

## Context

This is the execution plan for **task T2** of the Query API (`GET /api/v1/audit-events`)
work, decomposed in [`../tasks.md`](../tasks.md). T2 sits between the persistence layer (T1)
and the REST handler (T4):

```
T1 ──► T2 ──┐
            ├──► T4 ──► (feature complete)
T1 ──► T3 ──┘
T1 ┄┄► T5            (T5 independent; recommended to land after T1)
```

**Goal of T2.** Expose the paginated query on the **input port** (`QueryAuditEventUseCase`)
and implement the **application-layer rules** in `AuditEventService`:

1. `from > to` ⇒ return an empty page (no port call, no error) — a *valid* request with no
   results (`design.md` §3, §6).
2. otherwise, if `to − from` exceeds **90 days** ⇒ throw `InvalidTimeRangeException` (the API
   layer maps this to HTTP `422 INVALID_TIME_RANGE` in T4; `design.md` §3, §3.1, §6).
3. otherwise ⇒ delegate to `LoadAuditEventPort.find(query)` and report whether more pages may
   exist (`hasMore`), which the API layer turns into `nextCursor` (`design.md` §4.3).

No writes occur on the query path; `record` / `findById` / `findByActor` behaviour is
unchanged (the existing actor-only handler and its use-case/port methods are kept, per the
product decision in `tasks.md`).

**Sources.**
- [`../requirements.md`](../requirements.md) — US1 (confirm/refute an action; no-match ⇒ `200`
  empty; no state change), US3 (last page ⇒ `nextCursor: null`), §4 ("maximum window" open
  question — **superseded here** by the 90-day cap).
- [`../design.md`](../design.md) — §3 (`422` row; `200` is the only success status), §3.1
  (`INVALID_TIME_RANGE` error body), §4.3 (`nextCursor` present **iff** a full page of `limit`
  rows was returned), §6 (`from > to` ⇒ `200` empty page; 90-day max window), §8 FR1–FR6,
  §9 NFR, §10 (append-only / no read side effects; the `from`/`to` ordering rule and the
  90-day cap are **application-layer** concerns).
- [`../../../AGENTS.md`](../../../AGENTS.md) — append-only invariant; read operations have no
  side effects; hexagonal layering (domain has no Spring/JPA/HTTP deps; application depends on
  domain only); Google Java Format via Spotless.
- [`./T1-plan.md`](./T1-plan.md) — defines `AuditEventQuery`, `KeysetPosition`, and
  `LoadAuditEventPort.find(AuditEventQuery)` that this task consumes.

**Sizing.** One safe commit / PR: it compiles, `mvn verify` is green (Spotless + ArchUnit +
unit tests), no schema or data mutation.

## Scope

**In scope (this task):**
- New value record under `application.port.in`: `AuditEventPage(List<AuditEvent> events, boolean hasMore)`.
- New method on `QueryAuditEventUseCase`: `AuditEventPage query(AuditEventQuery query)`.
- New exception `domain.exception.InvalidTimeRangeException` (carries the configured max window
  / message).
- Implement `query` in `AuditEventService` (the three rules above).
- Unit tests for `AuditEventService`.

**Out of scope (later tasks):**
- Persistence query (`LoadAuditEventPort.find` + adapter) — **T1** (dependency).
- Opaque base64url cursor codec + filter-hash — **T3**.
- REST handler, request validation, response DTOs, the `@RestControllerAdvice` that maps
  `InvalidTimeRangeException` → `422` body and the `400` family → their bodies — **T4**.
- Flyway `V2` composite indexes — **T5**.

**Explicit notes:**
- The 90-day cap is an **application-layer** policy value (`design.md` §10): the constant lives
  in `AuditEventService`; `InvalidTimeRangeException` (in `domain.exception`, alongside the
  existing `AuditEventNotFoundException`) is just a typed carrier that receives the max-window
  value from the application layer.
- `from > to` is **not** a `422` — it is a valid request whose result happens to be empty
  (`design.md` §3, §6). The window check runs only *after* the `from > to` short-circuit, so it
  never sees a negative duration.
- `from == to` is allowed: it is neither `from > to` nor over-cap, so it delegates to the port,
  whose half-open `[from, from)` range returns no rows ⇒ `hasMore == false`.
- `hasMore = events.size() == query.limit()` — a full page may be the last page; the API layer
  still emits a `nextCursor` in that case and the *next* call returns an empty page. This
  matches `design.md` §4.3 ("`nextCursor` present iff the response returned a full page").

## Step-by-step implementation

### Step 1 — `AuditEventPage` record

New file
`src/main/java/com/cloudedir/auditlog/application/port/in/AuditEventPage.java`,
package `com.cloudedir.auditlog.application.port.in`:

```java
public record AuditEventPage(List<AuditEvent> events, boolean hasMore) {
  public AuditEventPage {
    events = List.copyOf(events); // null-checks + defensive immutable copy
  }
}
```

Records are allowed in `application.port..` (`HexagonalArchitectureTest.portsResideInApplicationLayer`
only requires *non-record* classes there to be interfaces).

### Step 2 — `InvalidTimeRangeException`

New file
`src/main/java/com/cloudedir/auditlog/domain/exception/InvalidTimeRangeException.java`,
package `com.cloudedir.auditlog.domain.exception`:

```java
public class InvalidTimeRangeException extends RuntimeException {
  private final Duration maxWindow;

  public InvalidTimeRangeException(Duration maxWindow) {
    super("Requested time window exceeds the maximum of " + maxWindow.toDays() + " days.");
    this.maxWindow = maxWindow;
  }

  public Duration maxWindow() {
    return maxWindow;
  }
}
```

A plain `RuntimeException` subclass (mirrors `AuditEventNotFoundException`). Uses only JDK
types (`java.time.Duration`) — no Spring/JPA/HTTP, so `domainHasNoDependencyOnOtherLayers`
still holds; it is in `domain.exception..`, not `domain.model..`, so the records-only rule
does not apply.

### Step 3 — extend `QueryAuditEventUseCase`

`src/main/java/com/cloudedir/auditlog/application/port/in/QueryAuditEventUseCase.java` — add:

```java
AuditEventPage query(AuditEventQuery query);
```

(Imports `AuditEventPage`, `AuditEventQuery` — both in the same package.) `findById` and
`findByActor` are untouched.

### Step 4 — implement `query` in `AuditEventService`

`src/main/java/com/cloudedir/auditlog/application/service/AuditEventService.java`:

```java
private static final Duration MAX_WINDOW = Duration.ofDays(90);

@Override
public AuditEventPage query(AuditEventQuery query) {
  if (query.from().isAfter(query.to())) {
    return new AuditEventPage(List.of(), false); // valid request, empty result — no port call
  }
  if (Duration.between(query.from(), query.to()).compareTo(MAX_WINDOW) > 0) {
    throw new InvalidTimeRangeException(MAX_WINDOW);
  }
  var events = loadPort.find(query);
  return new AuditEventPage(events, events.size() == query.limit());
}
```

`record` / `findById` / `findByActor` are unchanged. `savePort` is never touched on this path.
(`AuditEventService` is package-private and stays so.)

### Step 5 — formatting

Run `mvn spotless:apply` before committing (Google Java Format, per `AGENTS.md` § Code style).

## Test plan — `AuditEventServiceTest`

New file `src/test/java/com/cloudedir/auditlog/application/service/AuditEventServiceTest.java`
**in the same package** as `AuditEventService` (the class is package-private). Plain JUnit 5 +
Mockito (`mockito-core` comes transitively via `spring-boot-starter-test`; no Spring context):
mock `SaveAuditEventPort` and `LoadAuditEventPort`, construct `new AuditEventService(savePort, loadPort)`.

`@Test` methods (covering the T2 DoD):

1. **`from > to` ⇒ empty page, no port call.** `query(from, to=from.minus(1s), …)` ⇒ returned
   page has `events().isEmpty()` and `hasMore() == false`; `verifyNoInteractions(loadPort)`.
2. **Window exactly 90 days ⇒ allowed.** `to = from.plus(Duration.ofDays(90))` ⇒ delegates to
   `loadPort.find(...)` (stub it to return a list), no exception.
3. **Window just over 90 days ⇒ `InvalidTimeRangeException`.** `to = from.plus(Duration.ofDays(90)).plusNanos(1)`
   (or `.plusSeconds(1)`) ⇒ `assertThatThrownBy(...).isInstanceOf(InvalidTimeRangeException.class)`;
   `verifyNoInteractions(loadPort)`.
4. **`hasMore == true` when the port returns `limit` rows.** Stub `loadPort.find` to return a
   list of size `limit` ⇒ `page.hasMore() == true`.
5. **`hasMore == false` when the port returns fewer than `limit` rows** (incl. zero) ⇒
   `page.hasMore() == false`; `page.events()` equals the stubbed list.
6. **Query passed through unchanged.** Build an `AuditEventQuery` with every field populated
   (`from`, `to`, `actor`, `resourceType`, `resourceId`, `limit`, `after = new KeysetPosition(…)`),
   capture the argument to `loadPort.find` (`ArgumentCaptor` or `eq(query)`), assert it equals
   the input.
7. **No interaction with `SaveAuditEventPort`** on the query path ⇒ `verifyNoInteractions(savePort)`
   in the delegate-path tests.

(Optional but cheap: a couple of assertions that `findById` still throws `AuditEventNotFoundException`
when the port returns empty and that `findByActor` passes through — these paths currently have
no unit test.)

## Verification

- `mvn spotless:apply` then `mvn spotless:check` — formatting green.
- `mvn verify` — compiles; **`HexagonalArchitectureTest` green**: `AuditEventPage` is a record
  in `application.port.in` (so `portsResideInApplicationLayer` holds); `InvalidTimeRangeException`
  is in `domain.exception` and depends only on JDK types (so `domainHasNoDependencyOnOtherLayers`
  holds); `AuditEventService` (application) still depends only on `domain` + `application` ports.
  `AuditEventServiceTest` green; all pre-existing tests still green.
- Confirm **no Flyway migration** added/changed and `spring.jpa.hibernate.ddl-auto` stays
  `validate` — T2 changes no schema and writes no rows.

## Commit

Single commit / PR, e.g.: `feat: paginated query use case with from>to + 90-day cap (T2)`.

## Dependencies & follow-ups

- **Depends on T1** — uses `AuditEventQuery`, `KeysetPosition`, and `LoadAuditEventPort.find`.
  Can proceed in parallel with **T3** (cursor codec).
- **T3** — `api.cursor.CursorCodec` (base64url JSON `{v,t,id,f}`), `FilterFingerprint`,
  `InvalidCursorException`.
- **T4** — `GET /api/v1/audit-events` paginated handler (`params = {"from","to"}`), request
  validation + problem-style error contract (incl. mapping `InvalidTimeRangeException` ⇒
  `422 { code: "INVALID_TIME_RANGE", message, status: 422 }`), response DTOs, and `nextCursor`
  derived from `AuditEventPage.hasMore()` via the T3 codec.
- **T5** — Flyway `V2__query_api_indexes.sql`: composite indexes from `design.md` §7; drop the
  superseded `idx_audit_events_actor` / `idx_audit_events_timestamp`.
