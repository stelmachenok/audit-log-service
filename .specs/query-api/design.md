# Query API — Design

This is the authoritative design for the read-only `GET /api/v1/audit-events`
endpoint. The endpoint contract, status codes, sorting/pagination mechanics,
response body, and functional/non-functional requirements were consolidated here
from [`requirements.md`](./requirements.md); the problem statement (Overview, User
Stories, Out of scope, Open Questions) remains there. This document is
self-contained: it does not depend on, and does not reference, any other design
note.

## 1. Scope and relationship to requirements

`requirements.md` owns: the Overview, the EARS User Stories (US1–US3), the Out of
scope list, and the Open Questions. **This document owns**: the endpoint and its
query parameters, the full HTTP status-code contract, the sorting and pagination
design, the response body, the database indexes, the validation rules and edge
cases, and the explicit mapping to the invariants in `AGENTS.md`.

### 1.1. Domain vocabulary vs. current schema

This document uses the domain wording from `requirements.md` —
`occurredAt`, `actor.id` / `actor.type`, `resource.id` / `resource.type`,
`payload`. The persisted `audit_events` table today uses different column names;
the §7 index section uses the real column names. Mapping:

| Domain term (this doc, API)        | Current column (`audit_events`) | Notes                                              |
| ---------------------------------- | ------------------------------- | -------------------------------------------------- |
| `occurredAt`                       | `timestamp` (`TIMESTAMPTZ`)     | server-assigned event time                         |
| `actor.id`                         | `actor` (`VARCHAR`)             | `actor.type` is not modeled in the schema yet      |
| `resource.type`                    | `resource_type` (`VARCHAR`)     | nullable                                           |
| `resource.id`                      | `resource_id` (`VARCHAR`)       | nullable                                           |
| `action`                           | `action` (`VARCHAR`)            |                                                    |
| `payload`                          | `details` (`TEXT`)              | opaque to this API                                 |
| `id`                               | `id` (`UUID`, primary key)      | random UUID; not time-ordered (see §4.1)           |

## 2. Endpoint

```
GET /api/v1/audit-events
```

### 2.1. Query parameters

| Name           | Type                   | Required | Description                                                               |
| -------------- | ---------------------- | -------- | ------------------------------------------------------------------------- |
| `from`         | ISO-8601 instant (UTC) | Yes      | Inclusive lower bound of `occurredAt`.                                     |
| `to`           | ISO-8601 instant (UTC) | Yes      | Exclusive upper bound of `occurredAt`.                                     |
| `actor`        | string                 | No       | Exact match on actor identifier.                                          |
| `resourceType` | string                 | No       | Exact match on resource type (e.g. `order`).                              |
| `resourceId`   | string                 | No       | Exact match on resource identifier (e.g. `9f3b…`).                        |
| `cursor`       | string                 | No       | Opaque pagination token returned by a previous call. Absent = first page. |
| `limit`        | integer                | No       | Max number of records to return. Default `50`, maximum `1000`.            |

### 2.2. Filter rules

- `from` and `to` are mandatory; all other filters are optional and may be
  combined freely in any subset.
- `resourceType` and `resourceId` are independent and may be supplied
  individually or together.
- All filters are combined with logical AND.
- All filters are exact-match (no ranges or partial matches on `actor`,
  `resourceType`, `resourceId`).
- **Value normalization.** Filter values are trimmed of leading/trailing
  whitespace before use, and an `actor` / `resourceType` / `resourceId` that is
  absent, empty, or whitespace-only is treated as **no filter on that field** (not
  as a filter on the empty string). The exact same normalization is applied both
  when building the SQL query and when computing the cursor filter hash (§4.3), so
  the two can never disagree.

### 2.3. Coexistence with the legacy `?actor=` handler

A `GET /api/v1/audit-events?actor=…` handler predates this contract and is
**retained**. Selection between the two is by query parameters:

- both `from` and `to` present ⇒ the **paginated contract** in this document
  (`actor`, if also supplied, is just one more optional equality filter);
- `actor` present but **not** both bounds ⇒ the **legacy actor-only handler**,
  which returns the actor's events in its own (pre-existing) shape.

Known routing edge: a request that supplies exactly **one** bound plus `actor`
(e.g. `?from=…&actor=…`) does not match the paginated contract and falls through to
the legacy handler, so it returns `200` with the legacy actor list rather than
`400 MISSING_PARAMETER`. A request with one bound (or neither) and **no** `actor`
still returns `400` (§3). This edge is a known deviation, not a goal.

## 3. API contract — status codes

The endpoint returns one of three status families. No response — success or
error — mutates persisted state (see §10).

| Condition                                                                                                  | Status | Body                                            |
| ---------------------------------------------------------------------------------------------------------- | ------ | ----------------------------------------------- |
| Valid request, any number of matching rows (including zero)                                                 | `200`  | result page (§5); empty page if no matches      |
| Valid request with `from > to`                                                                              | `200`  | empty page `{ "data": [], "nextCursor": null }` |
| `from` or `to` missing                                                                                      | `400`  | error body (§3.1)                               |
| `from` or `to` not a valid ISO-8601 instant, or carrying a non-`Z` offset (offsets are currently rejected)  | `400`  | error body                                      |
| `limit` not an integer, or outside `[1, 1000]`                                                              | `400`  | error body                                      |
| `cursor` malformed / undecodable / unknown version                                                          | `400`  | error body                                      |
| `cursor` whose embedded filter hash does not match the current filter set (see §4.3)                        | `400`  | error body                                      |
| Requested time window `to − from` exceeds the 90-day cap (see §6)                                           | `422`  | error body                                      |

Notes:

- `200` is the *only* success status; the empty-result case, the "page after the
  last record" case, and the `from > to` case all return `200`, never an error.
- `422` is used *exclusively* for the over-cap time window. Every other
  client-side problem is `400`. (`from > to` is **not** a `422` — it is a valid
  request with an empty result.)
- Authentication and authorization are out of scope; no `401`/`403` contract is
  defined here. Rate limiting (`429`) is an Open Question in `requirements.md`.
- The "`from` or `to` missing ⇒ `400`" row assumes no `actor` is present. A request
  that supplies exactly one bound *and* `actor` is routed to the legacy handler
  (§2.3) and returns `200`, not `400`.
- When several problems coexist, the API layer's syntactic validation runs before
  the application layer sees the request: `from > to` *plus* a malformed `cursor`
  ⇒ `400 INVALID_CURSOR` (the cursor is decoded first), whereas `from > to` with a
  *valid* `cursor` ⇒ the `200` empty page (§4.3, §6).

### 3.1. Error body

Client errors return a small, stable, problem-style JSON object:

```json
{
  "code": "INVALID_TIME_RANGE",
  "message": "Requested time window exceeds the maximum of 90 days.",
  "status": 422
}
```

- `code` — a stable machine-readable identifier (e.g. `MISSING_PARAMETER`,
  `INVALID_INSTANT`, `INVALID_LIMIT`, `INVALID_CURSOR`, `INVALID_TIME_RANGE`).
- `message` — a human-readable explanation; not contractually stable.
- `status` — echoes the HTTP status code.

## 4. Sorting and pagination

### 4.1. Sort order and deterministic tie-breaker

Results are sorted **ascending by `occurredAt`**, with **`id` ascending as the
tie-breaker**:

```
ORDER BY occurredAt ASC, id ASC
```

`occurredAt` alone is not unique — the server can assign the same timestamp to two
events — so it cannot by itself define a stable page boundary. `id` is a random
`UUID`; it is not time-ordered, but it *is* unique, which is all the tie-breaker
needs. The pair `(occurredAt, id)` is therefore a total order over the rows, so two
requests for the same page always yield the identical sequence (satisfies User
Story 2 and the "same page → same sequence" requirement in User Story 3).

### 4.2. Pagination strategy — keyset, not offset

The endpoint uses **keyset (a.k.a. "seek") pagination**, not `LIMIT`/`OFFSET`.
The page boundary is carried in an opaque `cursor`; the next page is computed
relative to the `(occurredAt, id)` of the last row already returned.

Why keyset over offset:

- **Stability under concurrent appends.** The boundary is a *value*
  (`(occurredAt, id)`), not a row *count*. New events being ingested concurrently
  with pagination do not shift the boundary, so a page is never re-emitted and no
  matching row is skipped. With `OFFSET`, every insert (or delete) before the
  current position renumbers the remaining rows, which would duplicate or drop
  rows mid-iteration — directly contradicting User Story 3 and the append-only
  invariant.
- **Predictable cost.** `OFFSET n` makes the database scan and discard `n` rows
  for every page, so deep pages get progressively slower (O(n)). A keyset
  predicate `(occurredAt, id) > (?, ?)` is a single index range scan whose cost
  is independent of how deep the page is, and it stays index-backed under every
  supported filter combination (see §7) — which the non-functional requirements
  demand.
- **Opacity / evolvability.** The boundary is encoded into an opaque token, so
  the wire contract never exposes `LIMIT`/`OFFSET` and the internal cursor
  representation can change without breaking clients.

Implementation note: the persistence adapter issues the keyset query directly (the
`(occurredAt, id) > (?, ?)` predicate plus `LIMIT`); it never issues SQL `OFFSET`,
even if it happens to reuse an offset-capable paging API with the offset pinned to
zero.

### 4.3. Cursor format and semantics

- The `cursor` is **opaque** to the client: base64url (RFC 4648, **no padding**) of
  a small JSON object. The internal shape is an implementation detail — it is fixed
  in `api.cursor.CursorCodec`, not part of the wire contract, and may change; the
  `v` version tag guards old tokens. The current shape is:

  ```json
  { "v": 1, "t": "<occurredAt, ISO-8601 instant>", "id": "<last row id, canonical UUID>", "f": "<filter hash>" }
  ```

  (`t` and `id` are carried as strings so a vanilla JSON mapper round-trips them.)
- `f` is a hash of the **normalized filter set**, computed as follows:
  - take the five filter inputs in the fixed order `from`, `to`, `actor`,
    `resourceType`, `resourceId`;
  - normalize each value per §2.2 (trim; absent / empty / whitespace-only ⇒ the
    empty value);
  - render each as `name=value` and join the five with `\n`;
  - hash that string with **SHA-256** and base64url-encode the digest (no padding).

  Because the order is fixed, the hash is independent of query-parameter order; and
  because it uses the same normalization as the actual query (§2.2), a cursor whose
  `f` matches always describes the same SQL filter.
- Given a `cursor`, the next page is:

  ```
  SELECT … FROM audit_events
  WHERE <filters> AND (occurredAt, id) > (cursor.occurredAt, cursor.id)
  ORDER BY occurredAt ASC, id ASC
  LIMIT <limit>
  ```

  The row-value comparison `(occurredAt, id) > (?, ?)` may be lowered to the
  equivalent boolean form `occurredAt > ? OR (occurredAt = ? AND id > ?)` on stacks
  without a row-value operator; the two are semantically identical.
- No `cursor` ⇒ first page.
- `nextCursor` is present (a token) **iff** the response returned a *full* page of
  exactly `limit` rows; otherwise it is `null` (or omitted). A full page always
  yields a token, even when no further matching rows actually exist — in that case
  the next call returns an empty page with `nextCursor: null`. Iteration therefore
  terminates when a call returns fewer than `limit` rows (possibly zero); when the
  matching set's size is an exact multiple of `limit`, that is one extra (empty)
  call.
- A token that is undecodable, has an unknown `v`, or whose `f` does not match the
  filters supplied alongside it ⇒ `400` (treated as a malformed cursor). The
  filter-hash check prevents silently-wrong pagination when a client reuses a
  cursor against a different filter combination. This decode/check runs before the
  `from > to` short-circuit (§3, §6).
- A well-formed `cursor` supplied together with `from > to` still returns `200`
  with an empty page (the empty-range rule in §6 wins).
- Cursor lifetime / reuse-after-expiry semantics are an Open Question in
  `requirements.md`.

### 4.4. Internal value contracts

These types carry the query and its result across the API → application →
persistence boundary. They are internal (not part of the wire contract), but the
shapes are fixed here so the layers agree:

- `AuditEventQuery(Instant from, Instant to, String actor, String resourceType,
  String resourceId, int limit, KeysetPosition after)` — `from` and `to` non-null;
  `actor` / `resourceType` / `resourceId` already normalized per §2.2 (so `null`
  there means "no filter on that field"); `limit` already validated to `[1, 1000]`
  by the API layer; `after` non-null only when the request carried a valid
  `cursor`. The persistence adapter trusts these fields and does not re-validate
  them.
- `KeysetPosition(Instant occurredAt, UUID id)` — both non-null; the `(occurredAt,
  id)` of the last row of the previous page (the decoded cursor boundary).
- `AuditEventPage(List<AuditEvent> events, boolean hasMore)` — `events` is the page
  in `(occurredAt, id)` ascending order; `hasMore == (events.size() == limit)`. The
  API layer emits a `nextCursor` iff `hasMore`, consistent with the §4.3
  `nextCursor` rule.

## 5. Response

### 5.1. Body

```json
{
  "data": [
    {
      "id": "01HE…Z9",
      "occurredAt": "2026-04-17T11:02:14Z",
      "actor":    { "id": "u_42",  "type": null },
      "resource": { "id": "9f3b…", "type": "order" },
      "action":   "order.refunded",
      "payload":  "{\"amount\":1299,\"currency\":\"USD\"}"
    }
  ],
  "nextCursor": "…"
}
```

The example values are illustrative; the field-level rules below are normative
where they differ from it.

Field semantics:

- `id` — unique audit event identifier.
- `occurredAt` — server-assigned timestamp of the event (UTC, ISO-8601).
- `actor.id` — who performed the action.
- `actor.type` — **currently always `null`**: it is not modeled in the schema yet
  (see §1.1). The field is still emitted (as `null`) for forward compatibility.
- `resource.type` — what kind of thing was acted upon; `null` when not recorded.
- `resource.id` — the stored `resource_id` value verbatim; it carries no `type/`
  prefix. `null` when not recorded.
- `action` — domain action name.
- `payload` — the stored `details` value (`TEXT`), passed through **verbatim as a
  JSON string** (or `null`); this API does not re-parse it into a JSON object. (If
  `details` ever becomes `jsonb`, this would instead be an embedded JSON object.)
- `nextCursor` — present (a token) when a full page of `limit` rows was returned
  (see §4.3); `null` otherwise. The implementation always includes the field, using
  `null` rather than omitting it.

### 5.2. Empty results

An empty page is represented as `{ "data": [], "nextCursor": null }` with
HTTP `200`. This applies to "no matches", invalid ranges (`from > to`), and the
page after the last record.

## 6. Validation rules and edge cases

- **Required parameters.** `from` and `to` are mandatory; a request missing
  either ⇒ `400`. Open-ended ranges are not allowed — both bounds must be present.
- **Instant format.** `from` and `to` must be valid ISO-8601 instants in UTC with
  the `Z` suffix. Other offsets (e.g. `+02:00`) are currently rejected ⇒ `400`.
  Note that a lenient parser such as Java's `Instant.parse` *accepts* offset forms
  and silently normalizes them to UTC, so the API layer must reject a non-`Z`
  offset with an explicit check rather than relying on the parser to fail. (Whether
  to accept and normalize offsets is an Open Question in `requirements.md`.)
- **`from > to`.** Treated as a *valid* request with an empty result: `200`,
  `data: []`, `nextCursor: null`. Never an error.
- **`from == to`.** Also valid: `[from, from)` is an empty range, so it returns
  `200`, `data: []`, `nextCursor: null`. Not an error.
- **Maximum time window.** If `to − from` exceeds **90 days**, the request is
  rejected with `422` (`code: INVALID_TIME_RANGE`); a window of exactly 90 days is
  allowed. This concrete 90-day cap is a design decision that supersedes the
  "maximum `from`/`to` window" Open Question in `requirements.md`, pending product
  sign-off; it is a fixed constant in the application layer (not configuration). If
  the value changes, only this section and the §3 table change.
- **Empty filters.** A request with only `from` and `to` (no `actor`,
  `resourceType`, or `resourceId`) is valid and returns every event whose
  `occurredAt` is in `[from, to)`, paginated normally.
- **`limit`.** Default `50`; minimum `1`; maximum `1000`. A non-integer value, or
  a value outside `[1, 1000]`, ⇒ `400`.
- **Half-open range.** `[from, to)` includes events with `occurredAt == from` and
  excludes events with `occurredAt == to`.
- **`cursor`.** A malformed, undecodable, unknown-version, or filter-mismatched
  cursor ⇒ `400`; no server state is altered. A well-formed cursor combined with
  `from > to` still ⇒ `200` empty page. Cursor lifetime/reuse remains an Open
  Question in `requirements.md`.
- **Validation precedence.** The API layer runs syntactic checks in this order:
  parse `from` / `to` → check `limit` → compute the filter fingerprint → decode
  `cursor` → invoke the use case (which then applies `from > to` and the 90-day
  cap). Consequence: a malformed `cursor` is reported (`400 INVALID_CURSOR`) even
  when `from > to` would otherwise have produced a `200` empty page; a valid
  `cursor` with `from > to` still yields the `200` empty page (`after` is ignored
  in that case).
- **Filter normalization.** `actor` / `resourceType` / `resourceId` are trimmed,
  and an empty or whitespace-only value is treated as absent (§2.2) — for both the
  query and the cursor filter hash (§4.3).
- **Filter combinations.** All optional filters are exact-match, combined with
  AND, and may appear in any subset; `resourceType` and `resourceId` are
  independent.

## 7. Database indexes for all filters

The persisted table is `audit_events` with columns `id` (`UUID`, primary key),
`actor`, `action`, `resource_type`, `resource_id`, `details`, `timestamp`
(`TIMESTAMPTZ`). The filterable inputs are: the **always-present** `timestamp`
range, plus optional equality on `actor`, `resource_type`, and `resource_id`. The
ordering and the keyset boundary are on `(timestamp, id)`.

Proposed index set (to be delivered later as a Flyway `V2` migration — DDL only,
no data mutation, so consistent with the append-only invariant):

| Index (columns, all `ASC`)              | Serves                                                                       |
| --------------------------------------- | ---------------------------------------------------------------------------- |
| `(timestamp, id)`                       | range-only queries; also the global sort/keyset ordering                     |
| `(actor, timestamp, id)`                | `actor` filter + range + keyset, no separate sort step                       |
| `(resource_type, timestamp, id)`        | `resourceType` filter + range + keyset                                       |
| `(resource_id, timestamp, id)`          | `resourceId` filter + range + keyset; also the most selective index when both `resourceType` and `resourceId` are given |

Why this covers **all** supported filter combinations:

- Every index leads with an equality-filtered column (or, for the first, with the
  mandatory range column) and ends with `(timestamp, id)`, so the range scan and
  the keyset/sort are satisfied by one index with no extra sort node.
- The `timestamp` range is present in *every* query and is contained in *every*
  index, so even the no-optional-filter case is index-backed.
- For multi-filter combinations (`actor` + a resource filter, or
  `resourceType` + `resourceId`), PostgreSQL uses the most selective single index
  (in practice `(resource_id, timestamp, id)`) and applies the remaining
  equalities as cheap filters, or combines two of these via a bitmap-AND. Either
  way the query stays index-backed, satisfying the non-functional requirement.

The `V1` indexes `idx_audit_events_actor` and `idx_audit_events_timestamp`
(`timestamp DESC`) become redundant once the above exist — `(actor, timestamp, id)`
supersedes the first, and `(timestamp, id)` ascending matches this endpoint's sort
direction better than the descending one — so the same future migration would drop
them. Dropping `idx_audit_events_actor` does not regress the legacy `?actor=` query
(§2.3): `(actor, timestamp, id)` still serves `WHERE actor = ?`.

Operational and sequencing notes:

- The migration uses plain `CREATE INDEX`. On a large production `audit_events`
  table that holds a lock for the duration of the build; rolling the indexes out
  with `CREATE INDEX CONCURRENTLY` (which cannot run inside a transaction and so
  needs a non-transactional Flyway script) is an operational follow-up, not part of
  the baseline migration.
- Until this `V2` migration is applied, only the `V1` indexes
  (`idx_audit_events_timestamp` on `timestamp DESC`, `idx_audit_events_actor`)
  exist, so the ascending-sort and resource-filter cases are **not** index-backed.
  The §9 "index-backed under all filter combinations" guarantee holds only once
  `V2` has landed; sequence accordingly.

## 8. Functional requirements

1. The service must return only events whose `occurredAt` is in `[from, to)`.
2. The service must apply optional filters (`actor`, `resourceType`,
   `resourceId`) as exact-match AND conditions.
3. The service must return results in ascending `occurredAt` order.
4. The service must support cursor-based pagination so that iterating pages
   reaches every matching record exactly once, even when concurrent inserts occur
   (the append-only invariant guarantees stability).
5. The service must reject `limit` values outside the supported range.
6. The service must not modify any persisted state.

## 9. Non-functional requirements

- Read operations must not violate the append-only invariant defined in
  `AGENTS.md` (no `UPDATE`, no `DELETE`, no `INSERT` triggered by the query path).
- Queries must remain index-backed under all supported filter combinations — once
  the §7 `V2` index set is in place (before that, only the `V1` indexes exist; see
  §7). This rests on the §7 index design (one most-selective index per query plus
  residual equality filters, or a bitmap-AND of two of them); it is a design
  argument, not in itself enforced by an automated `EXPLAIN` assertion unless one
  is added against a dataset large enough that the planner prefers index scans.

## 10. Mapping to the `AGENTS.md` invariants

| Invariant (`AGENTS.md`)                                  | How this design upholds it                                                                                                                                                                                                                                                                                              |
| -------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Append-only — no `UPDATE`/`DELETE`, only `INSERT`**    | The query path issues `SELECT` statements only; it performs no `INSERT`/`UPDATE`/`DELETE` and installs no triggers. Pagination correctness *relies* on this: because rows are never mutated or removed, a row already returned keeps its `(occurredAt, id)`, and the strictly-greater keyset predicate `(occurredAt, id) > cursor` never re-yields it; concurrently appended rows only extend the tail. Hence no gaps, no duplicates (User Story 3). |
| **`timestamp` is set by the server only**                | The endpoint never accepts or writes `occurredAt`; `from` and `to` are read-side filter bounds and are not persisted anywhere. The query cannot influence any stored timestamp.                                                                                                                                          |
| **`actor` is mandatory**                                 | The `actor` filter is an exact match on a column guaranteed to be non-null, so the filter needs no special null handling; this is consistent with `actor` being required at write time.                                                                                                                                  |
| **Read operations must not create side effects**         | `GET /api/v1/audit-events` performs only `SELECT`, writes no rows, and emits no audit event for the query itself; only standard request logging occurs. Error responses (`400`, `422`) likewise change no persisted state.                                                                                                |
| **Architectural rules (supporting)**                     | The opaque-cursor codec lives in the API/infrastructure layer, never in `domain` (keeps `domain` free of HTTP/encoding concerns); syntactic validation (param presence, instant format, `limit` bounds, cursor decoding) sits in the API layer, while the `from`/`to` ordering rule and the 90-day cap are application-layer concerns — so the API layer carries no business logic; infrastructure depends on `domain`, never the reverse. |
