# Query API — Spec Eval Report (2026-05-13)

Checklist run against `.specs/query-api/` (`requirements.md`, `design.md`, `tasks.md`).
Verdicts: **PASS** / **FAIL** / **WEAK**, with one line of evidence each.

| #  | Item                                         | Verdict  |
|----|----------------------------------------------|----------|
| 1  | Each AC is testable                          | **PASS** |
| 1a | — AC: same page ⇒ same sequence              | **PASS** |
| 1b | — AC: concurrent appends ⇒ no repeat/skip    | **PASS** |
| 2  | Pagination strategy is justified             | **PASS** |
| 3  | Tasks have refs and DoD                       | **PASS** |
| 4  | Dependencies between tasks are explicit       | **PASS** |

## Evidence

- **1 — Each AC is testable — PASS.** All US1–US3 ACs (`requirements.md` §2.1–§2.3)
  resolve to observable outcomes: status code, `data` / `nextCursor` body fields,
  ascending `(occurredAt, id)` order, half-open `[from, to)` boundary, "no rows
  mutated". `tasks.md` DoD blocks (T1, T2, T4) carry a concrete test case for each.
- **1a — "two requests for the same page yield the same sequence" — PASS.**
  `design.md` §4.1 pins the total order `(occurredAt, id)`, so the determinism AC is
  checkable (T1 DoD: "deterministic tie-break when two rows share `timestamp`").
- **1b — "while new events are appended concurrently … no repeat/skip" — PASS.**
  `requirements.md` US3 now states it as two observable clauses ("never re-emit a
  returned event" / "deliver every event matched at iteration start exactly once"),
  and `design.md` §4.2 records why the append-only invariant makes those clauses
  fully testable by inserting rows between page fetches and walking the cursor — the
  exact scenario asserted in T1 and T4 DoD ("rows inserted between fetches do not
  cause repeats or skips"). No wall-clock concurrency test is required.
- **2 — Pagination strategy is justified — PASS.** `design.md` §4.2 picks keyset/seek
  over `LIMIT`/`OFFSET` and gives three grounded reasons — stability under concurrent
  appends (vs. `OFFSET` renumbering), O(1)-vs-O(n) cost, opaque/evolvable token; §4.1
  separately justifies the `id` tie-breaker.
- **3 — Tasks have refs and DoD — PASS.** `tasks.md` T1–T5 each carry a **Refs.** line
  (specific `design.md` §§ + `requirements.md` US numbers) and a **DoD.** block of
  `mvn verify`-checkable assertions.
- **4 — Dependencies between tasks are explicit — PASS.** `tasks.md` each task has a
  **Dependencies.** line; plus a "## Dependency graph" ASCII diagram and a suggested
  merge order (T1 → T3 → T2 → T4 → T5).

## Notes / non-blocking

- `requirements.md` §4 still lists the maximum-window item even though it is marked
  *resolved provisionally* — intentional (kept until product sign-off), not a defect.
- `design.md` §9's "index-backed under all filter combinations" NFR is a design
  argument, gated on the `V2` migration (T5) and only optionally `EXPLAIN`-tested —
  already flagged in §7/§9; not a checklist failure but worth tracking.

## Summary

4/4 checklist items **PASS**, all sub-items **PASS**. No **WEAK**, no **FAIL**.

### Changelog

- 2026-05-13: 1b raised **WEAK → PASS** after `requirements.md` US3 was restated as
  two observable clauses and `design.md` §4.2 documented why the append-only
  invariant makes the "insert between fetches" walk a complete test of the property.
