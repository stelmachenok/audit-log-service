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

## Architectural rules
- Java 21, Spring Boot 3, Maven
- Domain has no dependency on Spring, JPA, or HTTP
- API layer contains no business logic
- Infrastructure depends on domain, never the reverse
- No auto-DDL, no manual DB changes
- Compliance correctness > convenience
- All classes in `application.port..` must be interfaces or records (enforced by ArchUnit)
- Architectural constraints are verified automatically by `HexagonalArchitectureTest`

## Code style
- Google Java Format enforced via Spotless
- Before committing run `mvn spotless:apply` to auto-format
- CI runs `mvn spotless:check` as part of the `verify` phase
