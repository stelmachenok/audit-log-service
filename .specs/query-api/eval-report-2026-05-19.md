# Spec Evaluation: query-api

Date: 2026-05-19
Feature name: query-api
Overall status: PASS

## Summary

The query-api spec is complete, internally consistent, and satisfies every item on `.specs/_eval-checklist.md`. Acceptance criteria are written in EARS form with concrete, observable outcomes; every task carries explicit `Refs.` to `design.md` / `requirements.md` plus a `DoD.` of test-verifiable bullets; the pagination strategy is justified against the rejected alternative (OFFSET); and task dependencies are stated both per-task and as a consolidated dependency graph.

## Checklist Results

| Check item | Status | Evidence | Recommendation |
| --- | --- | --- | --- |
| Each AC is testable | PASS | `requirements.md` §2.1–§2.3 — every AC is an EARS-form, observable assertion (exact-match filters, half-open range, ascending sort, deterministic tie-break, `nextCursor` rules, malformed cursor ⇒ 400, no state change). `design.md` §4.2 explicitly notes append-only makes the concurrent-pagination AC "directly testable"; `tasks.md` T1/T4 DoD lists the matching integration tests. | None — keep ACs in EARS form when adding new ones. |
| Tasks have refs and DoD | PASS | `tasks.md` T1–T5 each have a `**Refs.**` line citing specific sections (e.g. T2 → `design.md §3, §3.1, §4.3, §6, FR1–FR6, NFR, §10`) and a `**DoD.**` block of bullets verifiable by `mvn verify` (unit/IT names, ArchUnit, Spotless). | None. |
| Pagination strategy is justified | PASS | `design.md` §4.2 ("Pagination strategy — keyset, not offset") gives three explicit reasons over OFFSET: stability under concurrent appends (rests on the append-only invariant), predictable O(1) cost vs OFFSET's O(n) deep-page scan, and wire opacity / evolvability via the opaque cursor. | None. |
| Dependencies between tasks are explicit | PASS | Each task has a `**Dependencies.**` line (e.g. T2 → T1; T4 → T2, T3; T5 → none / recommended-after-T1) and `tasks.md` ends with a `## Dependency graph` ASCII diagram and a suggested merge order **T1 → T3 → T2 → T4 → T5**. | None. |

## Critical Issues

- None.

## Weaknesses / Risks

- `requirements.md` §4 still lists five unresolved Open Questions (retention floor, cursor TTL, rate limiting, max response size, time-zone offsets, per-caller concurrency). Not a checklist failure, but they will become real before public launch.
- `design.md` §9 NFR ("index-backed under all filter combinations") is a design argument until an EXPLAIN-based assertion lands on a large enough dataset; `tasks.md` T5 DoD currently only asserts index *presence*, not plan shape.

## Suggested Improvements

- When closing any of the five Open Questions, mirror the resolution into `design.md` §6 (validation) and either §3 (status codes) or a new section, the same way the 90-day cap was resolved.
- Optional: add an EXPLAIN-based regression test alongside T5 to convert §9 from an argument into an enforced check.

## Final Recommendation

Proceed with implementation in the order T1 → T3 → T2 → T4 → T5 as `tasks.md` suggests. No checklist items block implementation; the listed weaknesses are follow-ups, not gates.
