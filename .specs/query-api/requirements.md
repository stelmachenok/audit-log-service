# Query API — Requirements

## 1. Overview

A read-only HTTP endpoint that returns audit events filtered by actor, resource,
and a mandatory time range. Results are paginated using an opaque cursor and
returned in chronological (ascending) order.

The endpoint must not produce any side effects (no writes, no log entries
beyond standard request logs).

> The endpoint contract, status codes, sorting/pagination mechanics, response
> body, and functional/non-functional requirements live in
> [`design.md`](./design.md).

## 2. User Stories

Acceptance criteria are written in EARS (Easy Approach to Requirements
Syntax) style: ubiquitous (`The system shall …`), event-driven
(`When …, the system shall …`), state-driven (`While …, the system
shall …`), optional-feature (`Where …, the system shall …`), and
unwanted-behavior (`If …, then the system shall …`).

### 2.1. User Story 1 — Compliance officer confirms or refutes an action

As a compliance officer, I need to confirm or refute that a specific
action occurred during an audit, so that I can produce defensible
findings for a regulator or internal review.

**Acceptance criteria**

- When a query supplies `from`, `to`, and `actor`, the system shall
  return only events whose `actor` matches exactly and whose
  `occurredAt` is in `[from, to)`.
- Where `resourceType` and/or `resourceId` are supplied, the system
  shall further restrict the result to events whose resource matches
  the supplied fields exactly.
- If no event matches the supplied filters, then the system shall
  return HTTP 200 with `data: []` and `nextCursor: null`.
- The system shall not modify any persisted state while serving the
  request.

### 2.2. User Story 2 — SRE reconstructs the timeline of actions on a resource

As an SRE responding to an incident, I need to reconstruct the
chronological sequence of actions taken against a specific resource,
so that I can identify the change that triggered the incident.

**Acceptance criteria**

- When a query supplies `resourceType` and `resourceId` together with
  `from` and `to`, the system shall return every audit event for that
  resource whose `occurredAt` is in `[from, to)`.
- The system shall return results sorted ascending by `occurredAt`.
- Where two events share the same `occurredAt`, the system shall order
  them deterministically so that two requests for the same page yield
  the same sequence.
- The system shall include events whose `occurredAt == from` and shall
  exclude events whose `occurredAt == to`.

### 2.3. User Story 3 — Security analyst paginates a large result set without loss or duplication

As a security analyst exporting a large slice of audit history, I need
to paginate the result set so that every matching event is delivered
exactly once, with no gaps and no duplicates, even while new events
continue to be ingested.

**Acceptance criteria**

- When more matching events exist than fit in one page, the system
  shall return a `nextCursor` whose use on the subsequent call
  continues the iteration from the position immediately after the last
  returned event.
- When the iteration reaches the last page, the system shall omit
  `nextCursor` or set it to `null`.
- While new audit events are being appended concurrently with
  pagination, the system shall not cause previously returned pages to
  repeat or skip rows (append-only invariant).
- If a malformed or undecodable `cursor` is supplied, then the system
  shall reject the request with HTTP 400 without altering server
  state.

## 3. Out of scope

- Authentication of the caller (no 401 contract is defined here).
- Authorization, multi-tenant / per-organization scoping of results, and
  cross-organization access controls.
- Full-text search over `payload`.
- Aggregations, counts, or analytics.
- Streaming / server-sent events.
- Range queries or partial matches on `actor`, `resourceType`, or
  `resourceId` (all filters are exact-match in this iteration).
- Sorting in descending order or by fields other than `occurredAt`.

## 4. Open Questions

The following values are currently undefined. They are recorded here so
that the implementation does not silently assume defaults; each item
needs an explicit product or operations decision before it can leave
this section.

- **Maximum `from`/`to` time window.** No upper bound on `to − from` is
  currently defined. Should requests with a very wide window be
  rejected, capped server-side, or accepted as-is? If rejected, with
  which status code and which limit? (`design.md` proposes a 90-day
  cap returning HTTP 422, pending product sign-off.)
- **Retention window.** Is there a lower bound on `from` (i.e. how far
  back queryable history extends)? Is data older than some threshold
  expected to return empty results, an error, or to remain available
  indefinitely?
- **Cursor lifetime.** How long does a `nextCursor` remain valid for
  reuse — a fixed TTL, indefinitely, or until a schema/index change?
  What is the behavior if a cursor is reused after that window?
- **Rate limiting.** Are there per-caller request-rate constraints on
  this endpoint, and if so how are they surfaced (HTTP 429, response
  headers, none)?
- **Maximum response payload size.** Separately from `limit`, is there
  an upper bound on serialized response body size to protect server and
  client from oversized pages (e.g. very large `payload` values)?
- **Time-zone input.** Inputs are currently restricted to UTC (`Z`
  suffix). Should offsets such as `+02:00` be accepted and normalized,
  or remain rejected?
- **Concurrency per caller.** Is there a maximum number of concurrent
  paginations a single caller may have open?
