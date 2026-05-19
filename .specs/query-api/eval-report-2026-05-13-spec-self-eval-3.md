# Spec Evaluation: query-api

Date: 2026-05-13
Feature name: query-api
Overall status: PASS

## Summary

The `query-api` spec is consistent and well-structured across `requirements.md`,
`design.md`, and `tasks.md`. All four checklist items are satisfied with concrete
evidence: every EARS-style acceptance criterion ties to an observable HTTP/DB
behavior, every task carries explicit `Refs.` and `DoD.` blocks, the keyset
pagination choice is justified against offset on three independent axes
(stability, cost, opacity), and inter-task dependencies are listed per task and
summarized in a dependency graph. No critical issues. The only soft point is
that several of the §4 Open Questions in `requirements.md` are still unresolved
(retention floor, cursor TTL, rate limiting, payload-size cap, offset
acceptance, per-caller concurrency) — they are correctly flagged as out of scope
for these tasks, but they will resurface before the endpoint is hardened for
production traffic.

## Checklist Results

| Check item | Status | Evidence | Recommendation |
| --- | --- | --- | --- |
| Each AC is testable | PASS | `requirements.md` §2.1–2.3 — every AC is phrased as a concrete observable: exact-match filter + `[from, to)` window (US1), ascending order with deterministic tie-break and `from`-inclusive / `to`-exclusive boundary (US2), full-page `nextCursor` semantics + no-repeat/no-skip under concurrent appends + `400` on malformed cursor (US3). `design.md` §4.2 also notes US3's concurrency clause is exercised by inserting rows between page fetches (append-only ⇒ no wall-clock race needed). | None — keep ACs phrased as observables. |
| Tasks have refs and DoD | PASS | `tasks.md` T1–T5 each have explicit `**Refs.**` (cite specific `design.md` sections and `requirements.md` user stories) and `**DoD.**` blocks listing test-checkable bullets (e.g. T1's IT covering ascending order, tie-break, half-open range, paging stability, filter subsets, no writes; T5's `pg_indexes` assertion). | None. |
| Pagination strategy is justified | PASS | `design.md` §4.2 ("Pagination strategy — keyset, not offset") argues keyset over offset on three axes: (1) stability under concurrent appends — boundary is a value not a count, plus an append-only argument that makes the guarantee deterministically testable; (2) predictable O(1)-per-page cost via index range scan vs. `OFFSET`'s O(n) scan-and-discard; (3) opacity/evolvability of the wire token. | None — the argument is complete and grounded in §7 indexes and §10 append-only invariant. |
| Dependencies between tasks are explicit | PASS | `tasks.md` — each task ends with `**Dependencies.**` (T1: None; T2: T1; T3: T1; T4: T2, T3; T5: None to compile, recommended after T1). Also summarized as an ASCII dependency graph and a suggested merge order (T1 → T3 → T2 → T4 → T5) in the "Dependency graph" section. | None. |

## Critical Issues

- None.

## Weaknesses / Risks

- `requirements.md` §4 leaves six Open Questions unresolved (retention floor,
  cursor TTL, rate limiting, max response body size, non-`Z` offset acceptance,
  per-caller concurrency). The spec explicitly defers them and `tasks.md`
  acknowledges this is intentional, but each is a production-readiness concern
  that will need a decision before the endpoint is exposed to untrusted
  callers.
- The "exactly one bound + `actor`" routing edge in `design.md` §2.3 is
  documented as a known deviation (falls through to the legacy handler instead
  of returning `400 MISSING_PARAMETER`). It is not a defect of the spec, but a
  client that supplies only one bound will get a silently-different response
  shape — worth a regression test in T4's DoD if not already covered.
- The 90-day cap in `design.md` §6 is described as "provisional, pending
  product sign-off." If the value changes after T2/T4 land, the constant
  changes in code and the §3/§6 sections change in the spec — low-cost, but
  worth tracking.

## Suggested Improvements

- Add an explicit DoD bullet in T4 for the "one-bound + `actor`" edge so the
  legacy fall-through is locked in by a test, matching the §2.3 routing note.
- Once product signs off on the 90-day window, fold the resolution back into
  `requirements.md` §4 (delete the bullet) so it stops appearing as open.
- Consider noting in T3's DoD that the cursor codec is also robust to a
  payload that decodes as JSON but is missing required fields (e.g. only `v`
  and `t`, no `id`) — `design.md` §4.3 implies this should also be
  `INVALID_CURSOR`, and the current DoD covers garbage/version/hash but not
  structural-shape mismatch.

## Final Recommendation

Proceed with implementation in the order suggested by the dependency graph
(T1 → T3 → T2 → T4 → T5). No critical gaps block work. Track the unresolved
Open Questions and the provisional 90-day cap as follow-up items rather than
as blockers for this feature.
