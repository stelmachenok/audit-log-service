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

### 4.3. Cursor format and semantics

- The `cursor` is **opaque** to the client: a base64url-encoded JSON object. Its
  internal shape is an implementation detail and may change (the version tag below
  guards old tokens).
- It carries: a `v` version tag, the `occurredAt` of the last returned row, the
  `id` of the last returned row, and `f` — a hash of the **normalized filter set**
  (`from`, `to`, `actor`, `resourceType`, `resourceId`; canonical ordering and
  encoding before hashing).
- Given a `cursor`, the next page is:

  ```
  SELECT … FROM audit_events
  WHERE <filters> AND (occurredAt, id) > (cursor.occurredAt, cursor.id)
  ORDER BY occurredAt ASC, id ASC
  LIMIT <limit>
  ```

- No `cursor` ⇒ first page.
- `nextCursor` is present (a token) **iff** the response returned a full page of
  `limit` rows and more rows may exist beyond it; otherwise it is `null` (or
  omitted). When the iteration reaches the last page, `nextCursor` is `null`.
- A token that is undecodable, has an unknown `v`, or whose `f` does not match the
  filters supplied alongside it ⇒ `400` (treated as a malformed cursor). The
  filter-hash check prevents silently-wrong pagination when a client reuses a
  cursor against a different filter combination.
- A well-formed `cursor` supplied together with `from > to` still returns `200`
  with an empty page (the empty-range rule in §6 wins).
- Cursor lifetime / reuse-after-expiry semantics are an Open Question in
  `requirements.md`.

## 5. Response

### 5.1. Body

```json
{
  "data": [
    {
      "id": "01HE…Z9",
      "occurredAt": "2026-04-17T11:02:14Z",
      "actor":    { "id": "u_42",      "type": "user" },
      "resource": { "id": "order/9f3b…", "type": "order" },
      "action":   "order.refunded",
      "payload":  { }
    }
  ],
  "nextCursor": "…"
}
```

Field semantics:

- `id` — unique audit event identifier.
- `occurredAt` — server-assigned timestamp of the event (UTC, ISO-8601).
- `actor.id`, `actor.type` — who performed the action.
- `resource.id`, `resource.type` — what was acted upon.
- `action` — domain action name.
- `payload` — event-specific structured data; opaque to this API.
- `nextCursor` — present (a token) when more pages exist; `null` or omitted
  otherwise.

### 5.2. Empty results

An empty page is represented as `{ "data": [], "nextCursor": null }` with
HTTP `200`. This applies to "no matches", invalid ranges (`from > to`), and the
page after the last record.

## 6. Validation rules and edge cases

- **Required parameters.** `from` and `to` are mandatory; a request missing
  either ⇒ `400`. Open-ended ranges are not allowed — both bounds must be present.
- **Instant format.** `from` and `to` must be valid ISO-8601 instants in UTC with
  the `Z` suffix. Other offsets (e.g. `+02:00`) are currently rejected ⇒ `400`.
  (Whether to accept and normalize offsets is an Open Question in
  `requirements.md`.)
- **`from > to`.** Treated as a *valid* request with an empty result: `200`,
  `data: []`, `nextCursor: null`. Never an error.
- **Maximum time window.** If `to − from` exceeds **90 days**, the request is
  rejected with `422` (`code: INVALID_TIME_RANGE`). This concrete 90-day cap is a
  design decision that supersedes the "maximum `from`/`to` window" Open Question
  in `requirements.md`, pending product sign-off; if the value changes, only this
  section and the §3 table change.
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
them.

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
- Queries must remain index-backed under all supported filter combinations.

## 10. Mapping to the `AGENTS.md` invariants

| Invariant (`AGENTS.md`)                                  | How this design upholds it                                                                                                                                                                                                                                                                                              |
| -------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Append-only — no `UPDATE`/`DELETE`, only `INSERT`**    | The query path issues `SELECT` statements only; it performs no `INSERT`/`UPDATE`/`DELETE` and installs no triggers. Pagination correctness *relies* on this: because rows are never mutated or removed, a row already returned keeps its `(occurredAt, id)`, and the strictly-greater keyset predicate `(occurredAt, id) > cursor` never re-yields it; concurrently appended rows only extend the tail. Hence no gaps, no duplicates (User Story 3). |
| **`timestamp` is set by the server only**                | The endpoint never accepts or writes `occurredAt`; `from` and `to` are read-side filter bounds and are not persisted anywhere. The query cannot influence any stored timestamp.                                                                                                                                          |
| **`actor` is mandatory**                                 | The `actor` filter is an exact match on a column guaranteed to be non-null, so the filter needs no special null handling; this is consistent with `actor` being required at write time.                                                                                                                                  |
| **Read operations must not create side effects**         | `GET /api/v1/audit-events` performs only `SELECT`, writes no rows, and emits no audit event for the query itself; only standard request logging occurs. Error responses (`400`, `422`) likewise change no persisted state.                                                                                                |
| **Architectural rules (supporting)**                     | The opaque-cursor codec lives in the API/infrastructure layer, never in `domain` (keeps `domain` free of HTTP/encoding concerns); syntactic validation (param presence, instant format, `limit` bounds, cursor decoding) sits in the API layer, while the `from`/`to` ordering rule and the 90-day cap are application-layer concerns — so the API layer carries no business logic; infrastructure depends on `domain`, never the reverse. |
