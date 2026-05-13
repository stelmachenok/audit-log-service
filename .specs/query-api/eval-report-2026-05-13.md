# Spec Evaluation: query-api

Date: 2026-05-13
Feature name: query-api
Overall status: FAIL

## Summary

The `query-api` specification cannot be fully evaluated because the required evaluation checklist is missing from both supported locations. The required feature spec files are present: `.specs/query-api/requirements.md`, `.specs/query-api/design.md`, and `.specs/query-api/tasks.md`. Per the `spec-self-eval` skill rules, a missing checklist is a critical failure.

## Checklist Results

| Check item | Status | Evidence | Recommendation |
| --- | --- | --- | --- |
| Checklist availability | FAIL | Missing `.specs/_eval-checklist.md` and `references/_eval-checklist.md`. | Add the evaluation checklist to `.specs/_eval-checklist.md` or `references/_eval-checklist.md`, then rerun this skill. |
| Required spec files present | PASS | Found `.specs/query-api/requirements.md`, `.specs/query-api/design.md`, and `.specs/query-api/tasks.md`. | No action needed for file presence. |
| Full checklist evaluation | FAIL | Cannot evaluate checklist coverage without a checklist source file. | Rerun after adding the checklist. |

## Critical Issues

- Missing checklist file at `.specs/_eval-checklist.md`.
- Missing fallback checklist file at `references/_eval-checklist.md`.
- Checklist-based PASS/FAIL/WEAK assessment cannot be completed without one of those files.

## Weaknesses / Risks

- The spec appears to have the expected core files, but no checklist-backed judgment can be made about completeness, consistency, or implementation readiness.
- Any manual assessment performed without the canonical checklist risks using criteria that differ from the repository standard.

## Suggested Improvements

- Add the canonical checklist to `.specs/_eval-checklist.md` if it is intended to be spec-local.
- Add the checklist to `references/_eval-checklist.md` if it is intended to be shared reference material.
- Rerun `spec-self-eval` for `query-api` after the checklist exists.

## Final Recommendation

Stop checklist-based approval until the evaluation checklist is added. The spec files are present, but the required evaluation source is missing, so the current result is `FAIL`.
