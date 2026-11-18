# backend/CLAUDE.md

Loaded when you work under `backend/`. The root `CLAUDE.md` still applies.

Before editing here, read `docs/BACKEND.md` (module and package layout) and, for anything touching cases, `docs/DOMAIN.md`.

## Module rules

- `nocook-domain` has **zero** dependencies except JUnit/AssertJ for tests. No Spring, no Jackson, no SQL. If you need an import from `org.springframework`, you are in the wrong module.
- `nocook-service` depends on `nocook-domain`. It never depends on `mock-oms`.
- `mock-oms` depends on nothing in this repo.
- `nocook-sim` depends on `nocook-domain` only.

## Boot 4 checklist (verify in every new pom)

- `spring-boot-starter-webmvc` (+ `spring-boot-starter-webmvc-test` for tests)
- `spring-boot-starter-flyway` + `org.flywaydb:flyway-database-postgresql`
- `spring-boot-starter-jdbc`, `org.postgresql:postgresql`
- `spring-boot-starter-amqp`
- `spring-boot-starter-actuator`, `spring-boot-starter-opentelemetry`
- `spring-boot-starter-validation`
- Tests: `spring-boot-testcontainers`, Testcontainers PostgreSQL + RabbitMQ modules, `org.awaitility:awaitility`
- Jackson 3 (`tools.jackson.databind.json.JsonMapper`)

If a name above is rejected by Maven, regenerate from start.spring.io and fix this list.

## Running

```bash
cd backend
./mvnw -q -pl nocook-domain test                 # seconds
./mvnw -q -pl nocook-service -am verify           # unit + Testcontainers ITs
./mvnw -q -DskipTests package                     # jars for Docker
```

Integration test classes end in `IT` and run under `maven-failsafe-plugin`. Unit tests end in `Test`.
