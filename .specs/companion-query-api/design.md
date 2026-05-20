# Audit query by actor/resource — design

Companion to [requirements.md](./requirements.md). The requirements document
fixes the *what*; this document fixes the *how*.

## API contract

### Endpoint

```
GET /audit-events
```

### Request — first page (no cursor)

| Param      | In    | Type            | Required             | Notes                                                |
|------------|-------|-----------------|----------------------|------------------------------------------------------|
| `actor`    | query | string          | One of actor/resource required | Comma-separated actor set with 1-10 values; exact matches are case-sensitive. |
| `resource` | query | string          | One of actor/resource required | Exact match; case-sensitive.                  |
| `from`     | query | ISO-8601 UTC    | yes                  | Inclusive lower bound on `event_timestamp`.          |
| `to`       | query | ISO-8601 UTC    | yes                  | Exclusive upper bound on `event_timestamp`.          |
| `limit`    | query | integer         | no (default 100)     | `1 ≤ limit ≤ 500`.                                   |

Examples:

```text
GET /audit-events?actor=svc:billing&from=2026-04-26T00:00:00Z&to=2026-05-03T00:00:00Z
GET /audit-events?actor=svc:billing,svc:orders&resource=invoice/4711&from=2026-04-26T00:00:00Z&to=2026-05-03T00:00:00Z
```

`actor` parsing is intentionally simple and deterministic:

- Split on comma.
- Trim surrounding whitespace around each token.
- Reject empty tokens after trimming.
- Deduplicate actor values after trimming.
- Sort the deduplicated actor set lexicographically before validation,
  cursor encoding, and repository access.

The actor-set semantics are order-insensitive: `actor=a,b` and `actor=b,a`
mean the same query. Actor values containing commas are not supported because
comma is the reserved separator.

### Request — subsequent page (cursor)

| Param    | In    | Type    | Required | Notes                                              |
|----------|-------|---------|----------|----------------------------------------------------|
| `cursor` | query | string  | yes      | Opaque token from a previous response.             |
| `limit`  | query | integer | no       | May change between pages; subject to same bounds.  |

When `cursor` is present, **none of** `actor`, `resource`, `from`, `to` may be
supplied — the cursor pins the filter set (AC-2.4). Mixing returns `400`.

### Response — `200 OK`

```jsonc
{
  "items": [
    {
      "id":        "5b9c…-uuid",
      "timestamp": "2026-05-03T14:22:09.871Z",
      "actor":     "svc:billing",
      "action":    "invoice.created",
      "resource":  "invoice/4711",
      "outcome":   "SUCCESS",
      "context":   { "amount": 199.0, "currency": "EUR" }
    }
  ],
  "nextCursor": "eyJ0cyI6Ij…", // null when hasMore == false
  "hasMore":    true
}
```

`hasMore == (nextCursor != null)` (AC-2.2). Items are ordered as defined in
*Sort & determinism* below.

### Errors — `400 Bad Request`

A single shape, returned by a `@RestControllerAdvice` handler:

```json
{ "error": "INVALID_REQUEST", "message": "from must be before to", "field": "from" }
```

The `field` value is nullable for errors that are not tied to a single
request parameter. The API does not use RFC 7807 in v1.

## Sort & determinism

- Final ORDER BY for every query: `event_timestamp DESC, id DESC`.
  - Primary key — `event_timestamp` — matches the auditor reading model
    (newest events first within an investigation window).
  - Secondary key — `id` (UUID v4, random) — guarantees a strict total order
    across rows that share a millisecond. Without it, two events emitted in
    the same instant could be visited in an indeterminate order across pages,
    breaking the keyset invariant.
- The table is immutable (Flyway V2 trigger blocks UPDATEs), so the
  `(timestamp, id)` ordering of any row is fixed for the lifetime of the row.
  This is what makes keyset pagination correct here: a cursor anchored on
  `(ts, id)` always partitions the table into "seen" and "unseen" sets, even
  while new events are being ingested at the head.
- New events ingested *after* a cursor is issued sort *before* the cursor
  (newer timestamp, DESC order) and therefore are not visited by that
  cursor's continuation — a snapshot-like read without any explicit
  transaction snapshot. This is the desired behaviour for an auditor working
  through a window: the result set does not silently grow underneath them.

## Pagination strategy with reasoning

### Choice — keyset over `(event_timestamp DESC, id DESC)`

#### Why not offset

At ~50M rows the offset/limit pattern fails the p95 ≤ 300ms target the moment
auditors page deep:

- `OFFSET k` forces PostgreSQL to scan and discard `k` rows on every request;
  cost grows linearly with depth.
- It is also unstable under concurrent ingest — every new event ahead of the
  current cursor effectively shifts subsequent offsets, so a row may appear
  twice or be skipped entirely as the user pages.

The current `GET /audit-events` uses offset; this design replaces it
(per the *Out of scope* clause in requirements: no backwards-compatibility
shim for offset). A legacy `offset` query parameter is not bound by the
controller and is silently ignored as an unknown parameter.

#### Why keyset

- Cost is `O(log n + page)` regardless of how deep the user has paged,
  because the index lets PG seek directly to the `(ts, id)` anchor.
- Stable under concurrent ingest (see *Sort & determinism* above).
- Plays naturally with the immutable, append-only model already in place.

### Cursor format

Opaque base64-url of a small JSON envelope:

```jsonc
{
  "ts":       "2026-05-03T14:22:09.871Z",
  "id":       "5b9c…-uuid",
  "actors":   ["svc:billing", "svc:orders"], // null if no actor filter
  "resource": null,
  "from":     "2026-04-26T00:00:00Z",
  "to":       "2026-05-03T00:00:00Z",
  "v":        2                    // schema version for forward compat
}
```

- **Encoding**: `Base64.getUrlEncoder().withoutPadding().encodeToString(json)`.
- **Decoding**: `400` on any parse error or unknown `v` (AC-2.5). Version
  `2` is the cursor shape for actor sets; older scalar-actor cursor shapes are
  rejected as unsupported.
- **Signing/TTL**: not signed and not expiring in this API version. The
  endpoint is read-only and currently unauthenticated; cursor protection is
  limited to parseability, supported version, and validation of the decoded
  envelope.
- **Pinned filters**: every cursor request decodes the envelope and reapplies
  `actors`, `resource`, `from`, `to` from inside it; client-supplied filter
  params alongside `cursor` cause a `400` (AC-2.4).
- **Envelope validation**: decoded cursors must contain at least one
  pinned `actors`/`resource`, valid `from`/`to`, `from < to`, and a window no
  wider than 7 days. If `actors` is present, it must contain 1-10 non-empty
  actor values in canonical sorted order. This keeps cursor requests bounded
  even though the cursor is unsigned.

### Page assembly — `LIMIT n+1`

Each page runs **one** query with `LIMIT limit + 1`. The (limit+1)-th row, if
present, is dropped from `items` and used solely to set `hasMore = true` and
to source the next `nextCursor`.

Why: a separate `COUNT(*)` over 50M rows would itself breach the p95 target
and is therefore unacceptable. A trailing "exists 1 row" probe would add a
second round-trip with no upside. `LIMIT n+1` gives `hasMore` for free at
the cost of fetching one extra row. `nextCursor` is built from the last
**kept** row (`items[limit-1]`); the dropped row is not exposed.

### Continuation predicate

Adding the keyset compare to the WHERE clause:

```sql
WHERE  event_timestamp >= :from
  AND  event_timestamp <  :to
  AND  (:actors   IS NULL OR actor    = ANY(:actors))
  AND  (:resource IS NULL OR resource = :resource)
  AND  (
        :cursor_ts IS NULL
        OR event_timestamp <  :cursor_ts
        OR (event_timestamp = :cursor_ts AND id < :cursor_id)
      )
ORDER  BY event_timestamp DESC, id DESC
LIMIT  :limit + 1;
```

Written as `(event_timestamp, id) < (:cursor_ts, :cursor_id)` in row-form
for clarity; the expanded form above is what we'll emit because PostgreSQL
plans it more reliably against the composite index.

For multi-actor requests, `:actors` is the canonical sorted actor array from
the Application layer. `resource`, when present, remains an AND filter over
the actor set: rows must satisfy both `actor = ANY(:actors)` and
`resource = :resource`.

## Indexes

### New indexes (Flyway `V3__add_keyset_indexes.sql`)

```sql
CREATE INDEX idx_audit_events_actor_ts_id
  ON audit_events (actor, event_timestamp DESC, id DESC);

CREATE INDEX idx_audit_events_resource_ts_id
  ON audit_events (resource, event_timestamp DESC, id DESC);
```

Including `id` as the trailing key column lets the keyset compare be served
entirely from the index without a heap visit per row to resolve same-instant
ties — the dominant cost driver for AC-3.1.

The actor index is also the index-backed path for AC-3.4 multi-actor requests:
PostgreSQL can constrain the leading `actor` key with `actor = ANY(:actors)`
for up to ten actor values, then apply the same timestamp/id keyset bounds.
Because multi-actor results must be globally ordered by
`event_timestamp DESC, id DESC` across all selected actors, the implementation
must verify the chosen plan with `EXPLAIN ANALYZE` and keep the result set
bounded by the 7-day window and `LIMIT limit + 1`.

### Indexes to drop

```sql
DROP INDEX idx_audit_events_actor_timestamp;
DROP INDEX idx_audit_events_resource_timestamp;
```

These are strict key-prefixes of the new indexes; PG will choose the new
indexes for any query the old ones served, so retaining them only doubles
the write amplification on every ingest. The feature is treated as
pre-production, so the cleanup migration uses plain Flyway `DROP INDEX`
rather than `DROP INDEX CONCURRENTLY`.

### Index kept

`idx_audit_events_timestamp ON (event_timestamp DESC)` is **kept** untouched.
The new query endpoint never uses it (AC-1.4 forbids time-only queries), but
it remains available for ad-hoc operational reads. Removing it is a
follow-up if and when ad-hoc usage is confirmed absent.

### Combined actor-set + resource queries

No combined `(actor, resource, event_timestamp, id)` index. PostgreSQL is
expected to use `idx_audit_events_actor_ts_id` for actor-only, multi-actor,
and actor-set + resource queries, then filter `resource` from the heap when a
resource is supplied. Two reasons to defer:

1. Combined queries are rare relative to single-filter ones; an extra index
   adds write amplification on every ingest.
2. Selectivity of a capped actor set of at most ten values is high enough in
   expected workloads to keep the heap-filter cost well under p95.

This **must be verified** by T9 with `EXPLAIN ANALYZE` against a synthetic
50M-row dataset before sign-off (AC-3.2, AC-3.4). If selectivity proves
insufficient for hot actor sets, add a specialized follow-up index rather than
guessing at one in the initial design.

### T9 performance verification

T9 is the final gate before merging the feature to `master`.

- Generate or load a synthetic 50M-row `audit_events` dataset with realistic
  actor/resource selectivity and timestamps spanning multiple windows.
- Run `EXPLAIN ANALYZE` for actor-only, multi-actor, resource-only, and
  combined actor-set/resource compliant requests. Plans must show the new V3
  keyset indexes (`idx_audit_events_actor_ts_id` or
  `idx_audit_events_resource_ts_id`) on the hot table.
- Measure server-side p95 latency for compliant first-page and cursor-page
  requests at representative `limit` values, including `100` and `500`.
  AC-3.1 passes only if p95 is ≤ 300ms.

## Validation rules

Validation runs in the Application layer, not the controller, so it is
covered by unit tests independent of Spring MVC.

| Rule | Source AC | Failure mode |
|------|-----------|--------------|
| Either `actor` or `resource` non-blank (when no cursor)        | AC-1.4 | `400` `MISSING_FILTER` |
| `actor` splits into 1-10 non-empty trimmed values              | AC-1.9, AC-1.10, AC-1.11, AC-1.12 | `400` `INVALID_ACTOR_SET` |
| `from` and `to` both supplied (when no cursor)                 | AC-1.3 | `400` `MISSING_PARAMETER`, `field` set to the first missing parameter |
| `from`, `to` parse as ISO-8601 instants that bind to UTC       | AC-1.7 | `400` `INVALID_TIMESTAMP` |
| `from < to`                                                    | AC-1.5 | `400` `INVALID_TIME_WINDOW` |
| `to − from ≤ Duration.ofDays(7)`                               | AC-1.6 | `400` `WINDOW_TOO_LARGE` |
| `1 ≤ limit ≤ 500` on first-page and cursor requests            | AC-2.6, AC-2.7 | `400` `LIMIT_OUT_OF_RANGE` |
| `cursor` parses as base64-url JSON, version 2                  | AC-2.5, AC-2.9 | `400` `INVALID_CURSOR` |
| Decoded cursor envelope contains valid pinned filters/window, including a valid canonical actor set when `actors` is present | AC-2.5, AC-2.9 | `400` `INVALID_CURSOR` |
| Mutual exclusion: `cursor` ⇔ none of `actor`/`resource`/`from`/`to` | AC-2.4 | `400` `CONFLICTING_PARAMETERS` |

Spring MVC binding handles timestamp parse failures at the API boundary.
Cross-field rules are enforced inside the Application service via
`AuditEventQuery.validate()`, which throws `ValidationException` carrying a
structured `ValidationError`. Cursor decode failures are wrapped into the
same exception path so the API advice has one validation response path.

## Integration with arch layers

```
HTTP request
   │
   ▼
┌─────────────────────────────────────────────────────────┐
│ API layer  (com.auditlog.api)                           │
│  AuditEventController                                   │
│   - @GetMapping("/audit-events")                        │
│   - parses query params and defaults limit              │
│   - calls AuditEventQueryService.queryPage(...)         │
│   - maps AuditEventPage → AuditEventPageResponse        │
│   - @RestControllerAdvice maps ValidationException → 400 │
└──────────────────────┬──────────────────────────────────┘
                       ▼
┌─────────────────────────────────────────────────────────┐
│ Application layer  (com.auditlog.application)           │
│  Records / value types:                                 │
│   AuditEventQuery       (validated request)             │
│   AuditEventCursor      (ts, id, actor set, filters, v) │
│   AuditEventPage        (items, nextCursor, hasMore)    │
│  Service:                                               │
│   AuditEventQueryService                                │
│     · validates request                                 │
│     · decodes and validates cursor envelopes            │
│     · calls AuditEventRepository.findPage(query,        │
│       cursorTs, cursorId)                               │
│  Port:                                                  │
│   AuditEventRepository.findPage(query, cursorTs,        │
│   cursorId)                                             │
└──────────────────────┬──────────────────────────────────┘
                       ▼
┌─────────────────────────────────────────────────────────┐
│ Infrastructure layer  (com.auditlog.infrastructure)     │
│  JpaAuditEventRepository implements AuditEventRepository│
│   - emits the SQL shown in *Pagination strategy*        │
│   - slices `limit+1` and builds nextCursor              │
│   - @Transactional(readOnly = true)                     │
│   - maps AuditEventEntity → AuditEvent (domain)         │
│  Flyway: V3__add_keyset_indexes.sql                     │
└─────────────────────────────────────────────────────────┘

Domain layer (com.auditlog.domain) is untouched:
  AuditEvent and AuditOutcome stay framework-free; no change.
```

### Type changes summary

- `AuditEventSearchCriteria` (existing) is **replaced** by
  `AuditEventQuery`. The new type carries the cursor and drops `offset`.
  Replacing rather than evolving avoids the temptation of a hybrid
  cursor/offset shape, which would violate AC-2.4.
- `AuditEventQuery.actor` becomes a canonical actor set rather than a scalar
  string: absent when no actor filter exists, otherwise a sorted,
  deduplicated list with 1-10 values.
- `AuditEventCursor` stores `actors` as the same canonical actor set and uses
  cursor version `2`.
- `AuditEventRepository.find(...)` is **replaced** by
  `AuditEventRepository.findPage(AuditEventQuery, Instant, UUID)` returning
  `AuditEventPage`. Single port method change, single integration test
  rewrite.
- `AuditEventQueryService.queryPage(...)` returns `AuditEventPage` instead of
  the legacy `find(...)` path returning `List<AuditEventView>`.

### Test coverage map (AC-5.4)

- Unit tests
  - `AuditEventCursorTest` — round-trip encode/decode with no actors,
    one actor, and multiple actors; version mismatch; malformed input.
  - `AuditEventQueryValidationTest` — every row of the *Validation rules*
    table, including trim/dedupe, empty actor tokens, and the 10-actor cap.
- Integration test
  - `AuditEventQueryRepositoryIntegrationTest` (Testcontainers PG 16) —
    seed N rows, page through with cursor across ≥ 3 pages, interleave
    concurrent inserts at the head, assert no row is seen twice and no row is
    skipped, and cover actor-set + resource filtering.
  - T9 performance verification — synthetic 50M-row dataset, server-side p95
    ≤ 300ms, and EXPLAIN ANALYZE proving the new keyset indexes are used for
    single-actor, multi-actor, resource-only, and actor-set + resource shapes.
- ArchUnit
  - Explicit assertions confirm `AuditEventQuery`, `AuditEventCursor`, and
    `AuditEventPage` live under `com.auditlog.application`.
  - API classes must not depend directly on `AuditEventCursor`; the API sees
    cursors only as opaque strings.

## Alignment with AGENTS.md

- **DDD-first** — Domain layer is not touched. `AuditEventQuery` and
  `AuditEventCursor` are Application-layer value types; `AuditEvent` itself
  remains framework-free.
- **Clean architecture layering** — Dependencies continue to point inward only.
  The new repository port `AuditEventRepository.findPage` is defined in
  Application; its sole implementation lives in Infrastructure; the API
  layer talks only to `AuditEventQueryService`.
- **Persistence rules** — Schema changes are limited to the new Flyway
  migration `V3__add_keyset_indexes.sql`. No JPA/Hibernate access leaks
  out of Infrastructure.
- **Testing strategy** — Unit tests cover validation and cursor logic with
  zero Spring context. The Testcontainers integration test exercises the
  real keyset SQL against PG 16. ArchUnit keeps the layering invariants
  green.
- **Layer boundaries** — API does not import any Infrastructure or JPA
  type. Persistence entities are not exposed past Infrastructure (we map to
  `AuditEvent`/`AuditEventView` at the boundary as today).
- **Ports & adapters** — `AuditEventRepository` stays the single port for
  audit-log persistence; we evolve its method signature, not its location.
- **Architecture enforcement** — The existing
  `ArchitectureRulesTest` continues to enforce
  *API ↛ Infrastructure*, *Application ↛ Infrastructure impls*,
  *Domain ↛ Spring/JPA*. New assertions keep `AuditEventQuery`,
  `AuditEventCursor`, and `AuditEventPage` in `com.auditlog.application` and
  keep cursor encoding out of the controller.
- **Smallest safe change** — One refined endpoint (no parallel `/v2`), one
  Flyway migration, one port-method evolution, one new pagination value
  type. Offset support is removed rather than maintained, in line with the
  *Out of scope* clause in requirements.
- **Branching** — Work lands on `feature/audit-query-by-actor-resource`
  (already cut from `master`). NOTES.md will be appended after the feature
  merges.
