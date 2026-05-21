# Spec Evaluation: query-api

Date: 2026-05-21
Feature name: query-api
Overall status: FAIL

## Summary

Re-evaluation after `design.md` was updated to match the multi-actor filter in
`requirements.md`. The requirements ↔ design contradiction flagged in the
earlier `2026-05-21` evaluation is **resolved**: `design.md` §2.1/§2.2/§3/§3.1/
§4.1/§4.3/§4.4/§6/§7 now describe the comma-separated `actor` list, the
`422 TOO_MANY_ACTORS` case, and an order-independent cursor hash. All four
checklist items now **PASS**.

However, `tasks.md` was **not** updated. It still describes `actor` as a single
exact-match string, enumerates only the four `400` error codes, and contains no
task implementing the actor-list parsing, the 10-actor cap, the `422`, or the
sorted-set cursor hash. `tasks.md` therefore now contradicts `design.md` and
`requirements.md`, and a committed requirement has no implementing task — a
critical gap. Overall status remains **FAIL** until `tasks.md` is reconciled.

## Checklist Results

| Check item | Status | Evidence | Recommendation |
| --- | --- | --- | --- |
| Each AC is testable | PASS | All ACs in `requirements.md` US1–US3 use EARS phrasing with concrete triggers/outputs, and their authoritative definitions in `design.md` are now consistent: §3 lists the `422` >10-actors row, §2.1/§2.2 model the `actor` list, §4.3 defines the sorted, order-independent cursor hash backing the US3 cursor-reuse AC. No remaining requirements↔design contradiction. | None — keep `design.md` and `requirements.md` in sync on future edits. |
| Tasks have refs and DoD | PASS | Every task T1–T5 in `tasks.md` has a section-level `**Refs.**` line and a per-task `**DoD.**` block (e.g. T2 → `design.md` §3/§3.1/§4.3/§6; T4 DoD enumerates status-code cases). The check item itself (presence of refs + DoD per task) is satisfied. | None for this item — but see Critical Issues: the tasks no longer *cover* the spec. |
| Pagination strategy is justified | PASS | `design.md` §4.2 names keyset/seek, names and rejects offset/limit, gives ≥2 requirement-tied arguments (concurrent-append stability → US3; O(1) vs O(n) deep-page cost → NFR; opaque cursor → evolvability) and states the end-of-set extra-empty-page trade-off (§4.3). §4.1 now also notes the multi-actor filter does not affect sort determinism. | None. |
| Dependencies between tasks are explicit | PASS | Each task has a `**Dependencies.**` line naming predecessor IDs and the depended-on artifact (e.g. T4 → "T2 … T3 (cursor codec)"); `tasks.md` includes an ASCII dependency graph and a merge order consistent with the per-task lines. | None. |

## Critical Issues

- **`tasks.md` is stale and now contradicts `design.md` / `requirements.md`.**
  - T4 describes `actor` as one of the "optional exact-match strings,
    AND-combined" — contradicts `design.md` §2.1/§2.2 (comma-separated list,
    OR within the set).
  - T4's error contract lists only `MISSING_PARAMETER`, `INVALID_INSTANT`,
    `INVALID_LIMIT`, `INVALID_CURSOR`; `TOO_MANY_ACTORS` / the `422` >10-actors
    case (now `design.md` §3/§3.1, §6) is absent.
  - T3's `FilterFingerprint` is described over single-value inputs; it does not
    cover the sorted, de-duplicated actor-set encoding now required by
    `design.md` §4.3.
  - No task implements actor-list splitting/trim/dedup, the 10-actor cap, or the
    multi-actor `actor IN (…)` query path. The "Requirements / design coverage"
    table maps US1/US3 to tasks that predate the multi-actor ACs.
- **A committed requirement has no implementing task.** An implementer working
  from `tasks.md` would build single-actor behavior only, silently omitting the
  multi-actor filter mandated by `requirements.md` §1/US1/US3.

## Weaknesses / Risks

- `tasks.md`'s "Decisions baked in" list and its "Out of scope" note still frame
  `actor` as single-valued; they will mislead until refreshed.
- The 10-actor cap and the 90-day cap are both fixed constants; T2's DoD tests
  the 90-day boundary but there is no equivalent boundary test (10 allowed, 11
  rejected) anywhere in `tasks.md`.

## Suggested Improvements

- Add a task (or extend T4) covering: comma-separated `actor` parsing with
  trim/drop-blank/dedup, the `>10 ⇒ 422 TOO_MANY_ACTORS` check in the API layer
  (per `design.md` §6 precedence), and the `actor IN (…)` query path. Include a
  boundary DoD (exactly 10 accepted, 11 rejected).
- Update T3 so `FilterFingerprint` covers the sorted, de-duplicated actor-set
  encoding from `design.md` §4.3, with a DoD test that re-ordered/duplicated
  actor lists produce the same `f`.
- Refresh `tasks.md`'s "Requirements / design coverage" table, "Decisions baked
  in" list, and the T4 error-code enumeration to include the multi-actor work.

## Final Recommendation

**Revise `tasks.md` before implementation.** `requirements.md` and `design.md`
are now mutually consistent and all four checklist items pass — solid progress
since the previous evaluation. The remaining blocker is confined to `tasks.md`:
it must be reconciled with the multi-actor feature (new/extended task, updated
T3/T4, refreshed coverage table) so every committed requirement has an
implementing task with refs and a DoD. Re-run this evaluation afterward.
