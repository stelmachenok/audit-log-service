## Project map
- Single-module Spring Boot service
- Layers: API → Application → Domain → Infrastructure
- One core aggregate: AuditEvent
- Database schema managed only via Flyway migrations
- Integration tests use Testcontainers + PostgreSQL
- AuditEvent is immutable after creation
 
## Invariants
- Append-only: no UPDATE or DELETE, only INSERT
- `timestamp` is set by the server only
- `actor` is mandatory
- Read operations must not create side effects

- compile
- Fixing code not existing tests, writing new tests that cover code changes
- codestyle check
- no merge without accept at least 2 reviewers

 
## Architectural rules
- Java 21, Spring Boot 3, Maven
- Domain has no dependency on Spring, JPA, or HTTP
- API layer contains no business logic
- Infrastructure depends on domain, never the reverse
- No auto-DDL, no manual DB changes
- Compliance correctness > convenience