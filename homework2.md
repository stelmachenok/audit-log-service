## Status
Спека: готово
План: готово
Реализация: готово

## AGENTS.md:
Что добавил после Занятия 2:

## Invariants
- List endpoints sort deterministically with an explicit tiebreaker
- Server-assigned timestamps must be truncated to microseconds (`Instant.now().truncatedTo(ChronoUnit.MICROS)`) before persistence — PostgreSQL `TIMESTAMPTZ` has microsecond precision, so a nanosecond-precision `Instant.now()` round-trips to a different value

## Architectural rules
- API layer does syntactic validation only (param presence, format, bounds, cursor decoding); business rules (range ordering, window caps, etc.) live in the application layer
- API errors use a problem-style JSON body `{ code, message, status }`, mapped centrally in `api.error.ApiExceptionHandler` — add new error codes there, never throw bare HTTP exceptions from controllers
- Flyway migrations are DDL only — no `INSERT`/`UPDATE`/`DELETE` and no triggers, consistent with the append-only invariant

## Build & test
- `mvn verify` runs Surefire (`*Test.java`) and Failsafe (`*IT.java`)
- Integration tests use Testcontainers + PostgreSQL — pass `-DskipITs` if no Docker daemon is available

## Specs
- Specs live in `.specs/<feature>/`, written in English, with EARS-style acceptance criteria
- The spec is the source of truth: when implementation and spec disagree, fix the spec first, then the code
- Before writing a new spec, agents must ask 5–7 clarifying questions to surface assumptions and unknowns


## Главная дельта «спека ↔ план»
Сильных различий замечено не было, некоторые неуточненные моменты связанные с тем, что детали отсутствовали в requrements.md и design.md были дописаны в плане:
- Сосуществование legacy-обработчика ?actor= — вся идея о существовании заранее имеющегося endpoint’а на том же пути присутствует только в tasks.md, а не в спецификации.
- Внутренности cursor не определены, каноническое кодирование с фиксированным порядком и нормализация фильтров null/blank/whitespace были полностью придуманы T3.
- Внутренние контракты value-type — структуры AuditEventQuery, KeysetPosition, AuditEventPage описаны только в tasks.md и планах, но отсутствуют в requirements.md / design.md.

## Где текущий подход с SDD сэкономит время\силы, где может оказаться overhead (на вашем РЕАЛЬНОМ проекте):

Полезен:
-	При реализации новой фичи или при обновленных требованиях к текущему функционалу, который подробно описан в спецификации, булет меньше дорогих переделок, ошибки будут найдены на уровне спецификации, а не после релиза или интеграции.
-	Сильно упрощает поддержку сложной системы

Overhead:
- на простых задачах, на мелком CRUD или быстрых фичах спецификация может стоить дороже самой реализации.

## Вопросы:

Каких-то конкретных вопросов нет, пока непривычно адаптироваться к большому количеству генерируемого кода

