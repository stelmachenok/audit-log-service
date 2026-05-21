# Spec Evaluation: query-api

Date: 2026-05-21
Feature name: query-api
Overall status: FAIL

## Summary

The `query-api` spec is well structured: tasks carry section-level refs and
per-task DoD blocks, dependencies are explicit with a graph, and the keyset
pagination strategy is thoroughly justified. However, the recently added
**multi-actor filter** in `requirements.md` (comma-separated `actor`, 10-actor
cap, HTTP `422`) is **not reconciled with `design.md` or `tasks.md`**. This
creates a direct contradiction on the status-code contract and leaves the new
acceptance criteria with no implementing task — a critical gap that must be
closed before implementation.

## Checklist Results

| Check item | Status | Evidence | Recommendation |
| --- | --- | --- | --- |
| Each AC is testable | WEAK | All ACs in `requirements.md` US1–US3 use EARS phrasing and name concrete triggers/outputs. But the new US1 multi-actor ACs reference behavior whose authoritative definition contradicts `design.md`: `requirements.md` §2.1 says ">10 distinct actor values ⇒ HTTP 422", while `design.md` §3 states "`422` is used *exclusively* for the over-cap time window. Every other client-side problem is `400`." `design.md` §2.1 still types `actor` as a single `string`. Per the rubric, a cross-doc contradiction caps this item at WEAK. | Reconcile `design.md` before implementation; once consistent, this returns to PASS. |
| Tasks have refs and DoD | PASS | Every task T1–T5 in `tasks.md` has a `**Refs.**` line with section-level citations (e.g. T2 → `design.md` §3, §3.1, §4.3, §6; `requirements.md` US1, US3) and a per-task `**DoD.**` block enumerated in `tasks.md` itself, beyond the uniform `mvn verify` preamble. | None for this item. (See Critical Issues: tasks do not yet *cover* the multi-actor feature.) |
| Pagination strategy is justified | PASS | `design.md` §4.2 "Pagination strategy — keyset, not offset" names keyset, names and rejects offset/limit, and gives ≥2 concrete arguments tied to requirements: concurrent-append stability → US3 exactly-once; O(1) vs O(n) deep-page cost → NFR; opacity/evolvability. Trade-off stated in §4.3 (one extra empty call when set size is a multiple of `limit`). | None. |
| Dependencies between tasks are explicit | PASS | Each task has a `**Dependencies.**` line citing predecessor IDs and *what* is depended on (e.g. T4 → "T2 (use case + `AuditEventPage` + `InvalidTimeRangeException`), T3 (cursor codec)"). `tasks.md` includes an ASCII dependency graph and a suggested merge order consistent with the per-task lines. | None. |

## Critical Issues

- **Status-code contradiction.** `requirements.md` §2.1 (US1) requires HTTP
  `422` for more than 10 actors, but `design.md` §3 explicitly reserves `422`
  "exclusively for the over-cap time window" and routes "every other
  client-side problem" to `400`. An implementer following `design.md` (declared
  authoritative in `tasks.md`) would not implement the multi-actor `422`.
- **`design.md` does not model the actor list.** §2.1 types `actor` as a single
  `string` ("Exact match on actor identifier"); §2.2 normalization and §4.3
  cursor filter-hash are all single-value. The multi-actor ACs in
  `requirements.md` §1 / US1 / US3 have no authoritative design counterpart.
- **No task covers the multi-actor feature.** `tasks.md` T1–T5 and its
  "Requirements / design coverage" table predate the multi-actor ACs. No task
  implements comma-separated parsing, trim/dedup normalization, the >10 ⇒ `422`
  rule, or an order/duplicate-independent cursor hash over an actor set.

## Weaknesses / Risks

- The new US3 AC — a `nextCursor` "shall remain usable when [the] same set is
  re-supplied in any order" — depends on a design mechanism that does not yet
  exist: `design.md` §4.3 hashes a single `actor` value, not a normalized,
  order-independent actor set. The AC is currently untestable against the design.
- `requirements.md` §3 (Out of scope) was updated to clarify set-membership is
  still exact-match, but `design.md` §2.2 ("All filters are exact-match (no
  ranges or partial matches on `actor`…)") is not similarly updated, so the two
  documents describe the `actor` filter differently.

## Suggested Improvements

- Update `design.md`: §2.1 (model `actor` as a list, max 10), §2.2 (per-element
  trim + dedup normalization), §3 / §3.1 (add the >10-actors case and a stable
  error `code`, e.g. `TOO_MANY_ACTORS`), and §4.3 (filter hash over a
  normalized, order-independent actor set).
- Reconsider whether `422` is the right status: `design.md` currently reserves
  `422` for the time-window cap and treats all other request-validation errors
  as `400`. A ">10 actors" cap is a request-shape error and may fit `400`
  better — resolve this deliberately rather than by document drift.
- Add a task (or extend T4) for multi-actor parsing/validation and the new
  cursor-hash behavior, and refresh the `tasks.md` coverage table for US1/US3.

## Final Recommendation

**Revise before implementation.** The spec is otherwise strong, but the
multi-actor filter currently lives only in `requirements.md` and contradicts the
authoritative `design.md` status-code contract while having no implementing
task. Reconcile `design.md` §2.1/§2.2/§3/§3.1/§4.3 and add task coverage, then
re-run this evaluation — the three PASS items should hold and "Each AC is
testable" should return to PASS.
