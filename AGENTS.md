## Project map
- Single-module Spring Boot service
- Layers: API → Application → Domain → Infrastructure
- One core aggregate: AuditEvent
- Database schema managed only via Flyway migrations
- Integration tests use Testcontainers + PostgreSQL
- AuditEvent is immutable after creation

## Package structure
- `api.controller` — REST controllers
- `api.dto` — request/response DTOs
- `api.mapper` — mapping between DTOs and domain models
- `application.port.in` — input ports (interfaces) and command records
- `application.port.out` — output ports (interfaces)
- `application.service` — use case implementations
- `domain.model` — domain records
- `domain.exception` — domain exceptions
- `infrastructure.persistence.adapter` — output port implementations
- `infrastructure.persistence.entity` — JPA entities
- `infrastructure.persistence.repository` — Spring Data JPA repositories

## Invariants
- Append-only: no UPDATE or DELETE, only INSERT
- `timestamp` is set by the server only
- `actor` is mandatory
- Read operations must not create side effects
- List endpoints sort deterministically with an explicit tiebreaker
- Server-assigned timestamps must be truncated to microseconds (`Instant.now().truncatedTo(ChronoUnit.MICROS)`) before persistence — PostgreSQL `TIMESTAMPTZ` has microsecond precision, so a nanosecond-precision `Instant.now()` round-trips to a different value

## Architectural rules
- Java 21, Spring Boot 3, Maven
- Domain has no dependency on Spring, JPA, or HTTP
- API layer contains no business logic
- API layer does syntactic validation only (param presence, format, bounds, cursor decoding); business rules (range ordering, window caps, etc.) live in the application layer
- API errors use a problem-style JSON body `{ code, message, status }`, mapped centrally in `api.error.ApiExceptionHandler` — add new error codes there, never throw bare HTTP exceptions from controllers
- Infrastructure depends on domain, never the reverse
- No auto-DDL, no manual DB changes
- Flyway migrations are DDL only — no `INSERT`/`UPDATE`/`DELETE` and no triggers, consistent with the append-only invariant
- Compliance correctness > convenience
- All classes in `application.port..` must be interfaces or records (enforced by ArchUnit)
- Architectural constraints are verified automatically by `HexagonalArchitectureTest`

## Code style
- Google Java Format enforced via Spotless
- Before committing run `mvn spotless:apply` to auto-format
- CI runs `mvn spotless:check` as part of the `verify` phase

## Build & test
- `mvn verify` runs Surefire (`*Test.java`) and Failsafe (`*IT.java`)
- Integration tests use Testcontainers + PostgreSQL — pass `-DskipITs` if no Docker daemon is available

## Specs
- Specs live in `.specs/<feature>/`, written in English, with EARS-style acceptance criteria
- The spec is the source of truth: when implementation and spec disagree, fix the spec first, then the code
- Before writing a new spec, agents must ask 5–7 clarifying questions to surface assumptions and unknowns

## Local automation
- The `spec-self-eval` skill lives in `.codex/skills/spec-self-eval/` and `.agents/skills/spec-self-eval/`; use it to evaluate `.specs/<feature>/requirements.md`, `design.md`, and `tasks.md` against `.specs/_eval-checklist.md`
- A Codex Stop hook is configured in `.codex/config.toml` and runs `.codex/hooks/spec-self-eval-on-stop.ps1` with status `Running spec-self-eval`
