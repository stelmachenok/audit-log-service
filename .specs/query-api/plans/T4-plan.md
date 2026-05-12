# T4 — API: `GET /api/v1/audit-events` paginated handler, DTOs, validation, error contract — Execution Plan

## Context

This is the execution plan for **task T4** of the Query API work, decomposed in
[`../tasks.md`](../tasks.md). T4 is the **integrating task** — it wires the persistence query
(T1), the application rules (T2), and the cursor codec (T3) into the actual HTTP endpoint, and
after it the feature is complete:

```
T1 ──► T2 ──┐
            ├──► T4 ──► (feature complete)
T1 ──► T3 ──┘
T1 ┄┄► T5            (T5 independent; recommended to land after T1)
```

**Goal of T4.** Add a new paginated handler on `GET /api/v1/audit-events` **alongside** the
existing actor-only handler, with full **request validation**, the **response body** from
`design.md` §5, and the **problem-style error contract** from §3 / §3.1. The API layer carries
only *syntactic* validation (param presence, instant format, `limit` bounds, cursor decoding)
plus the cursor codec wiring — the `from > to` rule and the 90-day cap live in the application
layer (T2), and `HexagonalArchitectureTest` is the guard for that split.

**Sources.**
- [`../requirements.md`](../requirements.md) — US1 (confirm/refute an action; optional resource
  narrowing; no-match ⇒ `200` empty; no state change), US2 (resource timeline; ascending; `from`
  inclusive / `to` exclusive), US3 (exactly-once pagination; last page ⇒ `nextCursor: null`;
  malformed cursor ⇒ `400`), §3 (Out of scope — no `401`/`403`).
- [`../design.md`](../design.md) — §2 / §2.1 (endpoint + query params), §2.2 (filter rules), §3
  (status-code contract; `200` is the only success), §3.1 (error body shape + stable codes), §4.1
  (sort order), §4.3 (cursor format, `nextCursor` present **iff** a full page was returned, filter-
  hash check, valid cursor + `from > to` ⇒ `200`), §5 / §5.1 / §5.2 (response body, empty page),
  §6 (validation rules & edge cases), §10 (codec lives in API; syntactic validation in API, the
  `from`/`to` ordering rule + 90-day cap in the application layer ⇒ API carries no business logic;
  no audit event emitted for the query; error responses change no persisted state). §1.1 — domain
  term ↔ column mapping (`occurredAt ← timestamp`, `actor.id ← actor`, `actor.type` not modeled ⇒
  `null`, `resource.* ← resource_type/resource_id`, `payload ← details`).
- [`../../../AGENTS.md`](../../../AGENTS.md) — hexagonal layering (API may depend on `domain` +
  `application`, never `infrastructure`; API contains no business logic, only routing & mapping);
  append-only / read operations have no side effects; Google Java Format via Spotless.
- [`./T1-plan.md`](./T1-plan.md), [`./T2-plan.md`](./T2-plan.md), [`./T3-plan.md`](./T3-plan.md) —
  define `AuditEventQuery` / `KeysetPosition`, `AuditEventPage` / `QueryAuditEventUseCase.query` /
  `InvalidTimeRangeException`, and `CursorCodec` / `FilterFingerprint` / `InvalidCursorException`.

**Sizing.** One safe commit / PR: it compiles, `mvn verify` is green (Spotless + ArchUnit +
web-slice + integration tests), no schema or data mutation.

## Scope

**In scope (this task):**
- New paginated handler on `GET /api/v1/audit-events`, coexisting with `findByActor`.
- Request validation → `400` family (`MISSING_PARAMETER`, `INVALID_INSTANT`, `INVALID_LIMIT`,
  `INVALID_CURSOR`); the over-cap window → `422 INVALID_TIME_RANGE` surfaced by mapping T2's
  `InvalidTimeRangeException`.
- New response DTOs (`AuditEventQueryResponse`, `AuditEventQueryItem`, nested actor/resource,
  `ErrorResponse`) + mapping in `api.mapper`.
- `nextCursor` wiring via `CursorCodec` + `FilterFingerprint` (T3).
- New `@RestControllerAdvice` for the error contract.
- Web-slice controller test + an end-to-end Testcontainers IT.

**Out of scope (other tasks / spec):**
- Persistence query — **T1**; the `from > to` short-circuit + 90-day cap **logic** — **T2**; the
  cursor codec internals — **T3**; Flyway `V2` indexes — **T5**.
- Authentication (`401`), authorization / multi-tenant scoping (`403`), rate limiting (`429`),
  full-text search, aggregations, streaming — per `requirements.md` §3.

**Explicit notes / decisions:**
- **Routing.** New handler: `@GetMapping(params = {"from", "to"})` ⇒ it serves
  `?from=…&to=…[&actor=…&resourceType=…&resourceId=…&cursor=…&limit=…]`. The legacy handler keeps
  no `params` condition and its required `actor`, so `?actor=…` (and `GET …/{id}`) are unchanged;
  Spring picks the params-conditioned handler when both `from` and `to` are present (it is the
  more specific mapping), so there is no ambiguity. **Residual edge:** a request supplying exactly
  *one* of `from`/`to` does **not** match the new handler and falls to the legacy handler — if
  `actor` is also missing that yields `400` via `MissingServletRequestParameterException` (mapped
  to `MISSING_PARAMETER` below); if `actor` is present it currently returns the legacy actor list.
  Tightening that single-bound-plus-actor case is left out of scope (the contract's primary path
  is "both bounds present"); the bare `?from`/`?to`/no-params requests still produce a `400`
  with the `MISSING_PARAMETER` code.
- **Instant parsing.** `from` / `to` are read as `@RequestParam(required = false) String` and
  parsed by a small helper that accepts only ISO-8601 instants with the `Z` offset (any other
  text or a non-`Z` offset ⇒ `INVALID_INSTANT`); a `null`/blank value ⇒ `MISSING_PARAMETER`
  (defensive — the `params` condition makes this unlikely). Parsing by hand (rather than letting
  Spring convert to `Instant`) keeps the error code/message under our control and makes the
  reject-non-`Z`-offset rule explicit.
- **`limit`.** `@RequestParam(name = "limit", defaultValue = "50") int limit`; a non-integer
  value surfaces as a `MethodArgumentTypeMismatchException` on parameter `limit` ⇒ mapped to
  `INVALID_LIMIT`; a parsed value outside `[1, 1000]` ⇒ `INVALID_LIMIT` from an explicit check.
- **`payload`.** The `details` column is `TEXT` (no JSON guarantee); `design.md` §1.1 calls it
  "opaque ... passed through unchanged". So `AuditEventQueryItem.payload` is the stored `String`
  (or `null`) emitted as-is — *not* re-parsed into a nested JSON object (the `{ }` in the §5.1
  example is illustrative). If `details` later becomes `jsonb`, revisit this mapping.
- **`actor.type`.** Not modeled in the schema ⇒ always serialized as `null` (§1.1).
- **No business logic in the API layer.** The handler does only: param presence, instant format,
  `limit` bounds, cursor decode/hash-check, `AuditEventQuery` assembly, use-case call, DTO
  mapping, `nextCursor` encoding. `from > to` and the 90-day cap are *not* re-implemented here —
  they come from `QueryAuditEventUseCase.query` (T2). `HexagonalArchitectureTest` enforces this.
- **No side effects.** `GET` issues `SELECT` only (via T1); error responses (`400`/`422`) write
  nothing; the endpoint emits no audit event for the query itself (only standard request logging).

## Step-by-step implementation

### Step 1 — response & error DTOs (`api.dto`)

New files under `src/main/java/com/cloudedir/auditlog/api/dto/`:

```java
public record AuditEventQueryResponse(List<AuditEventQueryItem> data, String nextCursor) {}

public record AuditEventQueryItem(
    UUID id,
    Instant occurredAt,
    AuditEventActor actor,
    AuditEventResource resource,
    String action,
    String payload) {}

public record AuditEventActor(String id, String type) {}        // type is always null today

public record AuditEventResource(String id, String type) {}     // id ← resource_id, type ← resource_type

public record ErrorResponse(String code, String message, int status) {}
```

(`AuditEventActor` / `AuditEventResource` may instead be nested inside `AuditEventQueryItem` —
implementation detail.) Records in `api.dto..` are unconstrained by ArchUnit.

### Step 2 — API-layer request exception (`api.error`)

New file `src/main/java/com/cloudedir/auditlog/api/error/InvalidRequestException.java`:

```java
public class InvalidRequestException extends RuntimeException {
  private final String code; // MISSING_PARAMETER | INVALID_INSTANT | INVALID_LIMIT

  public InvalidRequestException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String code() { return code; }
}
```

A plain `RuntimeException` carrying a stable code (mirrors the existing exception style). It is in
`api..` — fine for ArchUnit.

### Step 3 — `@RestControllerAdvice` for the error contract (`api.error`)

New file `src/main/java/com/cloudedir/auditlog/api/error/ApiExceptionHandler.java`:

```java
@RestControllerAdvice
class ApiExceptionHandler {

  @ExceptionHandler(InvalidTimeRangeException.class)      // from T2 (domain.exception)
  ResponseEntity<ErrorResponse> handle(InvalidTimeRangeException ex) {
    return body(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_TIME_RANGE", ex.getMessage());
  }

  @ExceptionHandler(InvalidCursorException.class)         // from T3 (api.cursor)
  ResponseEntity<ErrorResponse> handle(InvalidCursorException ex) {
    return body(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", ex.getMessage());
  }

  @ExceptionHandler(InvalidRequestException.class)        // explicit handler checks (Step 5)
  ResponseEntity<ErrorResponse> handle(InvalidRequestException ex) {
    return body(HttpStatus.BAD_REQUEST, ex.code(), ex.getMessage());
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  ResponseEntity<ErrorResponse> handle(MissingServletRequestParameterException ex) {
    return body(HttpStatus.BAD_REQUEST, "MISSING_PARAMETER",
        "Required parameter '" + ex.getParameterName() + "' is missing.");
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<ErrorResponse> handle(MethodArgumentTypeMismatchException ex) {
    var code = "limit".equals(ex.getName()) ? "INVALID_LIMIT" : "INVALID_INSTANT";
    return body(HttpStatus.BAD_REQUEST, code, "Parameter '" + ex.getName() + "' is invalid.");
  }

  private static ResponseEntity<ErrorResponse> body(HttpStatus s, String code, String message) {
    return ResponseEntity.status(s).body(new ErrorResponse(code, message, s.value()));
  }
}
```

It is picked up automatically by both the full context and `@WebMvcTest`. It does **not** touch
`AuditEventNotFoundException` — that handler's existing behaviour is unchanged (adding a `404`
mapping for it is a possible cleanup but is out of scope here).

### Step 4 — extend `AuditEventApiMapper`

`src/main/java/com/cloudedir/auditlog/api/mapper/AuditEventApiMapper.java` — add:

```java
public AuditEventQueryItem toQueryItem(AuditEvent e) {
  return new AuditEventQueryItem(
      e.id(),
      e.timestamp(),                              // occurredAt ← timestamp
      new AuditEventActor(e.actor(), null),       // actor.type not modeled
      new AuditEventResource(e.resourceId(), e.resourceType()),
      e.action(),
      e.details());                               // payload ← details (opaque, as-is)
}

public AuditEventQueryResponse toQueryResponse(List<AuditEvent> events, String nextCursor) {
  return new AuditEventQueryResponse(events.stream().map(this::toQueryItem).toList(), nextCursor);
}
```

Existing `toCommand` / `toResponse` are untouched. (`nextCursor` is computed in the controller —
the mapper stays free of the codec / fingerprint.)

### Step 5 — new handler in `AuditEventController`

`src/main/java/com/cloudedir/auditlog/api/controller/AuditEventController.java` — inject
`CursorCodec` (T3) and add the handler; keep `record`, `findById`, `findByActor` exactly as they
are:

```java
@GetMapping(params = {"from", "to"})
AuditEventQueryResponse query(
    @RequestParam(required = false) String from,
    @RequestParam(required = false) String to,
    @RequestParam(required = false) String actor,
    @RequestParam(required = false) String resourceType,
    @RequestParam(required = false) String resourceId,
    @RequestParam(required = false) String cursor,
    @RequestParam(name = "limit", defaultValue = "50") int limit) {

  Instant fromTs = RequestInstants.parseUtc(from, "from");   // → INVALID_INSTANT / MISSING_PARAMETER
  Instant toTs   = RequestInstants.parseUtc(to, "to");
  if (limit < 1 || limit > 1000) {
    throw new InvalidRequestException("INVALID_LIMIT", "limit must be in [1, 1000].");
  }

  var fingerprint = new FilterFingerprint(fromTs, toTs, actor, resourceType, resourceId);
  KeysetPosition after = (cursor == null) ? null : cursorCodec.decode(cursor, fingerprint); // → INVALID_CURSOR
  var query = new AuditEventQuery(fromTs, toTs, actor, resourceType, resourceId, limit, after);

  AuditEventPage page = queryUseCase.query(query);          // from>to ⇒ empty; >90d ⇒ InvalidTimeRangeException ⇒ 422
  String nextCursor =
      page.hasMore()
          ? cursorCodec.encode(lastPosition(page.events()), fingerprint)
          : null;
  return mapper.toQueryResponse(page.events(), nextCursor);
}

private static KeysetPosition lastPosition(List<AuditEvent> events) {
  var last = events.get(events.size() - 1);
  return new KeysetPosition(last.timestamp(), last.id());
}
```

`RequestInstants.parseUtc(String value, String paramName)` (new tiny helper in `api.controller`
or `api.error`): blank/null ⇒ `InvalidRequestException("MISSING_PARAMETER", …)`; otherwise
`Instant.parse(value)` and reject anything that is not a `Z`-suffixed instant ⇒
`InvalidRequestException("INVALID_INSTANT", …)`.

**Order of operations matters** (per `design.md` §6/§4.3): parse `from`/`to` → validate `limit`
→ build fingerprint → decode `cursor` (a malformed/unknown-version/hash-mismatch cursor ⇒ `400`
*before* the use case is called) → call `queryUseCase.query` (a well-formed cursor combined with
`from > to` then still yields the `200` empty page, because T2 returns an empty page without
consulting `after`).

### Step 6 — formatting

Run `mvn spotless:apply` before committing (Google Java Format, per `AGENTS.md` § Code style).

## Test plan

### `AuditEventQueryControllerTest` — `@WebMvcTest(AuditEventController.class)`

`@WebMvcTest(AuditEventController.class)` + `@Import({AuditEventApiMapper.class, CursorCodec.class,
ApiExceptionHandler.class})` (the advice is also auto-detected) + `@MockBean QueryAuditEventUseCase`
+ `@MockBean RecordAuditEventUseCase`; use `MockMvc`. Stub `queryUseCase.query(...)` per case.
Covers the validation/error matrix and routing:
- valid `from`/`to` only ⇒ `200`; body matches §5 (`data[].id/occurredAt/actor{id,type}/resource{id,type}/action/payload`,
  `actor.type == null`, `payload` echoes `details`); results in the order the use case returned them.
- each optional filter (`actor`, `resourceType`, `resourceId`) and a couple of combinations ⇒
  `200`; assert the `AuditEventQuery` handed to `queryUseCase.query` carries exactly those filters
  (`ArgumentCaptor`).
- missing `from` and/or `to` (and no `actor`) ⇒ `400` with `code == "MISSING_PARAMETER"`.
- malformed instant / non-`Z` offset (`from=not-a-date`, `from=2026-01-01T00:00:00+02:00`) ⇒
  `400 INVALID_INSTANT`.
- `limit = 0`, `limit = 1001`, `limit = abc` ⇒ `400 INVALID_LIMIT`; `limit = 1` and `limit = 1000`
  accepted ⇒ `200`; default (`limit` omitted) ⇒ `50` reaches the use case.
- `from > to` ⇒ `200` with `{ "data": [], "nextCursor": null }` — and the same with a *valid*
  `cursor` also present (stub `query` to return `new AuditEventPage(List.of(), false)`).
- `to − from > 90 days` ⇒ stub `query` to throw `InvalidTimeRangeException` ⇒ `422` with
  `{ "code": "INVALID_TIME_RANGE", "message": …, "status": 422 }`.
- garbage / wrong-version / filter-mismatched `cursor` ⇒ `400 INVALID_CURSOR` (build the
  mismatched cursor with a real `CursorCodec` against a different `FilterFingerprint`).
- `nextCursor` round-trip: stub `query` to return a full page (`size == limit`, `hasMore == true`)
  ⇒ response has a non-null `nextCursor`; feed it back on a second request with the *same* filters
  ⇒ `200` and the captured `AuditEventQuery.after` equals the `KeysetPosition` of the previous
  page's last row.
- routing: `GET /api/v1/audit-events?actor=u_1` still hits `findByActor` (returns the legacy
  `List<AuditEventResponse>` shape); `GET /api/v1/audit-events/{id}` still hits `findById`; a
  `?from&to&actor` request hits the new handler (new shape, `actor` applied).

### `AuditEventQueryControllerIT` — `@SpringBootTest(RANDOM_PORT)` + Testcontainers

Extend the existing IT pattern (`AuditEventControllerIT`: `@Testcontainers`, `postgres:16-alpine`,
`@DynamicPropertySource`, `TestRestTemplate`). End-to-end through Flyway-migrated PostgreSQL; seed
rows via the existing `POST /api/v1/audit-events` (server assigns `timestamp` — so order rows by
posting them in sequence; use unique actor/resource values per test for isolation since the table
is shared). Covers:
- valid query returns the seeded rows ascending by `(occurredAt, id)`, body shape per §5,
  `payload` pass-through, `actor.type == null`.
- each optional filter + a combination narrows the result correctly.
- `from > to` ⇒ `200` empty page; `to − from > 90 days` ⇒ `422 INVALID_TIME_RANGE`.
- **pagination walk:** page 1 with `limit = k` (< total) ⇒ non-null `nextCursor`; GET with that
  cursor (same filters) ⇒ the next `k` rows, no overlap, no gap; repeat to the last page ⇒
  `nextCursor: null`; **insert extra in-range rows between page fetches** ⇒ no repeats, no skips
  (append-only stability, US3).
- garbage `cursor` ⇒ `400 INVALID_CURSOR`.
- the pre-existing `createdEventIsImmutable` flow, `GET …?actor=…`, and `GET …/{id}` responses are
  unchanged.
- **no side effects:** capture row count (and stored `timestamp`s of the seeded rows) before/after
  a query — including the `400`/`422` cases — assert unchanged; assert no extra audit event was
  written for the query itself.

## Verification

- `mvn spotless:apply` then `mvn spotless:check` — formatting green.
- `mvn verify` — compiles; **`HexagonalArchitectureTest` green**: the new classes are all in
  `api..` (`api.dto`, `api.error`, `api.controller`, `api.mapper`), depend only on `domain`
  (`InvalidTimeRangeException`), `application.port.in` (`QueryAuditEventUseCase`, `AuditEventQuery`,
  `AuditEventPage`, `KeysetPosition`), `api.cursor` (`CursorCodec`, `FilterFingerprint`,
  `InvalidCursorException`), Spring web, and the JDK — **no `infrastructure` dependency**
  (`apiHasNoDependencyOnInfrastructure` holds); the `from > to` rule and the 90-day cap are not
  re-implemented in `api` (business logic stays in the application layer). `AuditEventQueryControllerTest`,
  `AuditEventQueryControllerIT`, and all pre-existing tests green.
- No Flyway migration, `spring.jpa.hibernate.ddl-auto` stays `validate` — T4 changes no schema.

## Commit

Single commit / PR, e.g.: `feat: paginated GET /api/v1/audit-events handler, DTOs, validation, error contract (T4)`.

## Dependencies & follow-ups

- **Depends on T2** (use case + `AuditEventPage` + `InvalidTimeRangeException`) and **T3**
  (`CursorCodec` + `FilterFingerprint` + `InvalidCursorException`), which in turn depend on **T1**.
  Suggested merge order from `tasks.md`: T1 → T3 → T2 → T4.
- **T5** — Flyway `V2__query_api_indexes.sql` (`design.md` §7): the four composite indexes
  `(timestamp, id)`, `(actor, timestamp, id)`, `(resource_type, timestamp, id)`,
  `(resource_id, timestamp, id)`, dropping the superseded `idx_audit_events_actor` /
  `idx_audit_events_timestamp`. Independent of T4; recommended after T1; may land any time.
- Possible later cleanups (not part of this feature): `404` mapping for `AuditEventNotFoundException`;
  tightening the "single bound + `actor`" routing edge described above.
