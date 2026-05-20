# Homework 3

## Status

- Skill `spec-self-eval`: есть.
- Stop hook (видимое блокирование на `FAIL` воспроизведено): да
- Multi-actor filter (спека + план + код): не завершил, в процессе
- Cross-check партнера пройден: да — Uladzislau Tratsiak.

## `AGENTS.md`

Что добавил(а) после Занятия 3:

AGENTS.md changes:
 Local automation
- The `spec-self-eval` skill lives in `.codex/skills/spec-self-eval/` and `.agents/skills/spec-self-eval/`; use it to evaluate `.specs/<feature>/requirements.md`, `design.md`, and `tasks.md` against `.specs/_eval-checklist.md`
- A Codex Stop hook is configured in `.codex/config.toml` and runs `.codex/hooks/spec-self-eval-on-stop.ps1` with status `Running spec-self-eval`


## Самое неочевидное, что поймал skill

tasks.md T9 says to capture the performance verification artifact under .specs/query-api/, while this feature lives under .specs/companion-query-api/. This is a path consistency risk, not a checklist blocker. Но это изза того что дикерти

## Где skill и hook сэкономили бы время, а где был бы overhead

При правильной настройке эффективно экономит время, но нужно грамотно составлять сам скилл и подбирать элементы для проверки в _eval-checklist.md

## Главные вопросы к Занятию 4

В данный момент нет