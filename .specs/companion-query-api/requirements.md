# Audit query by actor/resource — requirements

## Problem

Compliance officers, SREs, and security analysts need to answer questions like
*"what did actor X do to resource Y last week"* and *"what did these actors do
last week"* against the audit log.

The current `GET /audit-events` endpoint accepts `actor`, `resource`, `from`,
`to`, `limit`, `offset`, but:

- It has no validation that constrains the query to an index-friendly shape, so
  callers can submit unbounded queries that will not meet the p95 ≤ 300ms target
  once `audit_events` reaches ~50M rows.
- It uses offset pagination, which degrades sharply at deep offsets and cannot
  hold p95 ≤ 300ms at the target row count.
- Its response is a bare list, with no signal about whether more pages exist
  and no stable continuation token across concurrent ingestion.

This feature refines `GET /audit-events` into an auditor-grade read API with
mandatory bounding inputs, single-actor or multi-actor filtering, keyset/cursor
pagination, and a paginated response envelope, while keeping the existing
immutability and clean-architecture guarantees of the service.

## User stories with acceptance criteria

Acceptance criteria are written in EARS style (Ubiquitous, Event-driven,
Unwanted-behaviour). Every "the system" below refers to the audit-log service
in scope — specifically `GET /audit-events`.

### US-1 — Query by actor set and/or resource within a bounded time window

> As a compliance officer or security analyst, I want to retrieve audit events
> for one actor, several actors, and/or a resource within an explicit time
> window, so that I can investigate what actions occurred during an incident or
> audit period.

- **AC-1.1** (Event-driven) WHEN the client sends `GET /audit-events` with
  ISO-8601 UTC `from`, ISO-8601 UTC `to`, and at least one of `actor` or
  `resource`, THE SYSTEM SHALL return HTTP 200 with the matching events
  ordered by `event_timestamp` DESC, then `id` DESC as a deterministic
  tiebreaker, using exact case-sensitive matches for each actor value and
  `resource`.
- **AC-1.2** (Event-driven) WHEN both `actor` and `resource` are supplied,
  THE SYSTEM SHALL return only events matching both filters (logical AND),
  where `actor` matches any actor in the supplied actor set.
- **AC-1.3** (Unwanted) IF `from` is missing OR `to` is missing OR both are
  missing, THEN THE SYSTEM SHALL return HTTP 400 with a body identifying a
  missing parameter. When both are missing, the system MAY report the first
  missing parameter encountered.
- **AC-1.4** (Unwanted) IF neither `actor` nor `resource` is supplied, THEN
  THE SYSTEM SHALL return HTTP 400 with a body stating that at least one of
  `actor` or `resource` is required.
- **AC-1.5** (Unwanted) IF `from` is equal to or after `to`, THEN THE SYSTEM
  SHALL return HTTP 400.
- **AC-1.6** (Unwanted) IF `to − from` exceeds 7 days, THEN THE SYSTEM SHALL
  return HTTP 400 with a body stating the 7-day cap.
- **AC-1.7** (Unwanted) IF `from` or `to` is not a valid ISO-8601 instant
  that can be bound to a UTC `Instant`, THEN THE SYSTEM SHALL return HTTP 400.
- **AC-1.8** (Ubiquitous) THE SYSTEM SHALL treat `from` as inclusive and `to`
  as exclusive (`[from, to)`).
- **AC-1.9** (Event-driven) WHEN the client supplies `actor` as a
  comma-separated list of two to ten actor values, THE SYSTEM SHALL return
  events whose `actor` exactly matches any supplied actor value.
- **AC-1.10** (Ubiquitous) THE SYSTEM SHALL treat a single `actor` value as a
  one-item actor set, preserving the current single-actor request behaviour.
- **AC-1.11** (Unwanted) IF `actor` contains more than 10 actor values, THEN
  THE SYSTEM SHALL return HTTP 400 with a body stating the 10-actor cap.
- **AC-1.12** (Ubiquitous) THE SYSTEM SHALL NOT support actor values that
  contain commas; commas in the `actor` query parameter are reserved as actor
  separators.

### US-2 — Page through results deterministically

> As a security analyst, I want to page through results with a stable cursor,
> so that I see every event exactly once even when new events are being
> ingested concurrently.

- **AC-2.1** (Ubiquitous) THE SYSTEM SHALL return a JSON envelope of the form
  `{ "items": [...], "nextCursor": <string|null>, "hasMore": <boolean> }`.
- **AC-2.2** (Ubiquitous) THE SYSTEM SHALL set `hasMore = (nextCursor != null)`.
- **AC-2.3** (Event-driven) WHEN the client sends a request with a valid
  `cursor` parameter, THE SYSTEM SHALL return the next page of items strictly
  older than the cursor's `(event_timestamp, id)` anchor, while applying the
  filter set encoded in the cursor.
- **AC-2.4** (Unwanted) IF a `cursor` is supplied together with any of
  `actor`, `resource`, `from`, or `to`, THEN THE SYSTEM SHALL return HTTP 400,
  because the cursor pins the filter set.
- **AC-2.5** (Unwanted) IF the supplied `cursor` is malformed, not parseable,
  has an unsupported version, or decodes to an invalid pinned filter/window
  set, THEN THE SYSTEM SHALL return HTTP 400.
- **AC-2.6** (Ubiquitous) THE SYSTEM SHALL default `limit` to 100 and cap it
  at 500 on both first-page and cursor requests. Clients MAY change `limit`
  between cursor pages.
- **AC-2.7** (Unwanted) IF `limit` exceeds 500 OR is less than 1, THEN THE
  SYSTEM SHALL return HTTP 400.
- **AC-2.8** (Ubiquitous) THE SYSTEM SHALL produce an opaque, URL-safe
  `nextCursor` string (callers must not parse it).
- **AC-2.9** (Ubiquitous) THE SYSTEM SHALL encode the full actor set in the
  cursor filter set when the original request used one or more actors.

### US-3 — Predictable performance at scale

> As an SRE, I want the query API to stay within p95 ≤ 300ms at 50M rows, so
> that downstream audit dashboards remain responsive.

- **AC-3.1** (Ubiquitous) THE SYSTEM SHALL hold p95 ≤ 300ms for any compliant
  request when `audit_events` contains 50M rows, measured server-side.
- **AC-3.2** (Ubiquitous) THE SYSTEM SHALL execute queries through the
  keyset composite indexes `idx_audit_events_actor_ts_id` and
  `idx_audit_events_resource_ts_id`, verified with `EXPLAIN ANALYZE` on a
  synthetic 50M-row dataset.
- **AC-3.3** (Ubiquitous) THE SYSTEM SHALL be side-effect free: a query
  request SHALL NOT write any rows to `audit_events`, mutate existing rows,
  or modify any other persistent state.
- **AC-3.4** (Ubiquitous) THE SYSTEM SHALL provide an index-backed query path
  for compliant multi-actor time-window requests, verified with
  `EXPLAIN ANALYZE` on a synthetic 50M-row dataset.

### US-4 — Response shape

> As an auditor, I want every event in the response to carry the full context
> needed for investigation, so that I do not need a second round-trip per row.

- **AC-4.1** (Ubiquitous) THE SYSTEM SHALL include `id` (UUID), `timestamp`
  (ISO-8601 UTC string), `actor`, `action`, `resource`, `outcome`
  (`SUCCESS|DENIED|ERROR`), and `context` (JSON object) on every item.
- **AC-4.2** (Ubiquitous) THE SYSTEM SHALL serialize `timestamp` as the same
  instant the event was recorded with, in UTC.
- **AC-4.3** (Ubiquitous) THE SYSTEM SHALL NOT include rows from
  `audit_events_archive`.

### US-5 — Architectural conformance

> As the maintainer of this service, I want the new query path to follow the
> existing clean-architecture rules, so that ArchUnit tests keep passing and
> the system remains testable.

- **AC-5.1** (Ubiquitous) THE SYSTEM SHALL define cursor and request types in
  the Application layer; the API layer SHALL only translate HTTP
  ↔ Application DTOs.
- **AC-5.2** (Ubiquitous) THE SYSTEM SHALL keep all JPA/Hibernate access
  inside the Infrastructure layer, behind the existing
  `AuditEventRepository` port.
- **AC-5.3** (Ubiquitous) THE DOMAIN layer SHALL remain free of Spring, JPA,
  and any framework dependency.
- **AC-5.4** (Ubiquitous) ALL existing unit, integration, and ArchUnit tests
  SHALL continue to pass; new ACs SHALL be covered by:
  - unit tests for cursor encoding/decoding and validation rules,
  - a Testcontainers integration test that exercises pagination across at
    least 3 pages with concurrent inserts,
  - an ArchUnit assertion confirming the new request/response types live in
    the correct layer.

## Out of scope

- Authentication and authorization (no auditor role, no JWT, no mTLS); the
  endpoint stays unauthenticated as today.
- Querying `audit_events_archive` or any cross-table UNION.
- Filters beyond `actor`, `resource`, and time window — no `action`,
  `outcome`, or JSONB context search in v1.
- Calendar-week semantics; the API never infers "last week" — clients always
  supply explicit `from`/`to`.
- Time windows wider than 7 days.
- Mutation, deletion, or correction of audit events (already prevented by
  the V2 Flyway trigger).
- Streaming exports, CSV/Parquet downloads, or bulk extraction APIs.
- Multi-tenancy; there is no tenant column today and none is added here.
- Rate limiting, quotas, and per-caller throttling.
- Backwards-compatibility shim for the current offset/limit pagination —
  callers will migrate to cursor pagination as part of this change. A legacy
  `offset` query parameter has no effect and is ignored as an unknown
  parameter by the API layer.
- Logging/auditing the audit-query calls themselves ("audit the auditors").

## Resolved decisions

1. **Audit-the-auditors** — Reads of `GET /audit-events` are not recorded as
   audit events in v1.
2. **Cursor TTL / signing** — `nextCursor` is an unsigned, non-expiring,
   opaque base64-url JSON envelope in v1.
3. **Error body format** — Validation failures use the project-specific JSON
   shape `{ "error": "<code>", "message": "<text>", "field": "<field|null>" }`.
4. **Inclusive/exclusive `to`** — The API uses `[from, to)` semantics.
5. **Offset/limit deprecation timing** — The endpoint switches directly to
   cursor pagination. The old `offset` parameter is ignored if supplied.
6. **Total count** — The response omits a `total` field.
7. **Future auth handoff** — Authentication and authorization remain outside
   v1; the endpoint stays unauthenticated as today.
8. **Performance verification environment** — Performance sign-off uses a
   synthetic 50M-row dataset in the T9 verification task.
9. **Index-drop deployment assumption** — The legacy two-column indexes are
   dropped with plain Flyway `DROP INDEX` because the service is treated as
   pre-production for this feature.
10. **Multi-actor input** — The existing `actor` query parameter accepts a
    comma-separated set of one to ten actor values; exact case-sensitive
    matching is applied independently to each actor value.
