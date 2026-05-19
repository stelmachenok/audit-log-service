---
name: spec-self-eval
description: Evaluate a feature specification against the repository evaluation checklist and write a numbered, dated markdown report under .specs/<feature>/.
---

# Spec Self Eval

## When to Use

Use this skill when asked to evaluate, audit, review, or self-assess a feature specification in this repository against the spec evaluation checklist.

## Expected Inputs

- Feature name: `<feature>`
- Run from the repository root.
- Required spec files:
  - `.specs/<feature>/requirements.md`
  - `.specs/<feature>/design.md`
  - `.specs/<feature>/tasks.md`
- Checklist source, in priority order:
  1. `.specs/_eval-checklist.md`
  2. `references/_eval-checklist.md`

## Output Path

Write the report to:

```text
.specs/<feature>/eval-report-<date>-spec-self-eval-<eval_no>.md
```

Use the local current date in `YYYY-MM-DD` format for `<date>`.
Set `<eval_no>` to the next increasing integer for reports created for the same
feature on the same date: inspect existing files matching
`eval-report-<date>-spec-self-eval-*.md`, use `1` if none exist, otherwise use
one more than the highest existing number.

## Evaluation Logic

1. Confirm the current working directory is the repository root.
2. Read the checklist from `.specs/_eval-checklist.md`; if it does not exist, read `references/_eval-checklist.md`.
3. Read `requirements.md`, `design.md`, and `tasks.md` for the selected feature.
4. Do not modify `requirements.md`, `design.md`, or `tasks.md`.
5. If any required spec file is missing, create the eval report with overall status `FAIL` and list the missing file paths as critical issues.
6. If both checklist locations are missing, create the eval report with overall status `FAIL` and list both attempted checklist paths as critical issues.
7. Evaluate each checklist item against the three spec files. Cite specific filenames and section headings when available.
8. Use concise, actionable language. Prefer concrete evidence over broad judgment.
9. Assign each checklist item one status:
   - `PASS`: clearly satisfied with concrete evidence.
   - `FAIL`: missing, contradictory, or violates a required checklist item.
   - `WEAK`: partially covered, ambiguous, underspecified, or difficult to verify.
10. Assign the overall status:
   - `PASS`: spec is complete and consistent; no critical failures and only minor/no weaknesses.
   - `FAIL`: critical gaps, contradictions, missing required sections, missing required spec files, or missing checklist.
   - `WEAK`: usable but has ambiguity, weak detail, or partial checklist coverage.

## Report Format

Use this exact structure:

```markdown
# Spec Evaluation: <feature>

Date: <YYYY-MM-DD>
Feature name: <feature>
Overall status: PASS | FAIL | WEAK

## Summary

<short summary of the evaluation outcome>

## Checklist Results

| Check item | Status | Evidence | Recommendation |
| --- | --- | --- | --- |
| <item> | PASS/FAIL/WEAK | <filename/section evidence> | <actionable recommendation> |

## Critical Issues

- <critical issue or "None">

## Weaknesses / Risks

- <weakness or risk or "None">

## Suggested Improvements

- <improvement or "None">

## Final Recommendation

<clear recommendation: proceed, revise before implementation, or stop until critical gaps are fixed>
```

## Example Invocation

```text
Use the spec-self-eval skill to evaluate the query-api feature.
```

This evaluates `.specs/query-api/requirements.md`, `.specs/query-api/design.md`, and `.specs/query-api/tasks.md`, then writes `.specs/query-api/eval-report-<date>-spec-self-eval-<eval_no>.md`.
