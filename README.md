# Event Ticket Booking System

Scalable Services (SEZG583), Assignment 1. A microservices application for booking event tickets. Four Spring Boot services each own their own PostgreSQL database and talk to each other only through versioned REST APIs.

**Team:** Gairik · Shreya · Mudit

## Services

| Service | Folder | Port | Database | Owner | Responsibility |
|---|---|---|---|---|---|
| Event Catalog | [`services/event-catalog`](services/event-catalog/README.md) | 8081 | `catalog_db` | Shreya | Published events and ticket types |
| Inventory | [`services/inventory`](services/inventory/README.md) | 8082 | `inventory_db` | Shreya | Ticket capacity, atomic reservation and release |
| Booking | [`services/booking`](services/booking/README.md) | 8083 | `booking_db` | Gairik | Booking state, saga orchestration, idempotency |
| Payment (mock) | [`services/payment-mock`](services/payment-mock/README.md) | 8084 | `payment_db` | Mudit | Simulated charges, failure injection, repeat-safe payments |

Main transaction: **Booking** validates the event with **Catalog**, reserves tickets in **Inventory**, charges through **Payment**, then confirms the booking. If the payment fails, Booking releases the reservation instead.

## Prerequisites

- Java 21 (Maven itself is optional, since each service ships the Maven wrapper)
- Docker (for PostgreSQL)
- Python 3.10+ (for scripts in `scripts/`; standard library only)

## Quick start

```bash
# 1. Start PostgreSQL. Creates four databases, each with its own user.
docker compose up -d

# 2. Start each service in its own terminal (Booking connects to the other three):
cd services/event-catalog && ./mvnw spring-boot:run     # Port 8081
cd services/inventory     && ./mvnw spring-boot:run     # Port 8082
cd services/payment-mock  && ./mvnw spring-boot:run     # Port 8084
cd services/booking       && ./mvnw spring-boot:run     # Port 8083

# 3. Check health for each service
curl http://localhost:8081/health
curl http://localhost:8082/health
curl http://localhost:8083/health
curl http://localhost:8084/health
```

PostgreSQL is exposed on host port **5433** so it does not clash with a local install. Change it with `POSTGRES_HOST_PORT`.

| Database | User / password |
|---|---|
| `catalog_db` | `catalog_user` / `catalog_pass` |
| `inventory_db` | `inventory_user` / `inventory_pass` |
| `booking_db` | `booking_user` / `booking_pass` |
| `payment_db` | `payment_user` / `payment_pass` |

`CONNECT` is revoked from `PUBLIC`, so each user can open only its own database ([infra/postgres/01-init-databases.sql](infra/postgres/01-init-databases.sql)). To reset all data: `docker compose down -v`.

## Shared conventions (all services)

| Area | Convention |
|---|---|
| Stack | Java 21, Spring Boot 3.5, Maven wrapper, Spring Data JPA, Flyway, springdoc-openapi, JUnit 5 + Mockito |
| Paths | `/api/v1/...`, plus `GET /health` returning `{"status","service","database"}` |
| JSON | `snake_case` field names (`spring.jackson.property-naming-strategy: SNAKE_CASE`) |
| Errors | `{"timestamp","status","error","code","message","path","correlation_id","details"}`, where `code` is a stable UPPER_SNAKE value |
| Correlation | `X-Correlation-Id` header is read or generated, put in the MDC as `correlationId`, echoed on the response and forwarded on every outgoing call |
| Logs | JSON lines (logstash-logback-encoder) with a `service` field |
| Config | Environment variables only (`PORT`, `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `*_SERVICE_URL`, …). Each service has a `.env.example` |
| Unsafe POSTs | `Idempotency-Key` header. Same key + same payload replays the stored response; same key + different payload returns 409 |
| Data | No cross-service foreign keys or joins; other services' entities are referenced by id only |

## API documentation

OpenAPI specs are exported to [docs/openapi/](docs/openapi/). Each running service also serves Swagger UI at `/swagger-ui.html`.

## Business rules

| ID | Rule | Owner |
|---|---|---|
| BR1 | Reservation quantity never exceeds available capacity | Shreya |
| BR2 | Same key + same payload creates only one logical booking | Gairik |
| BR3 | Same key + different payload is rejected | Gairik |
| BR4 | Booking is confirmed only after payment succeeds | Gairik + Mudit |
| BR5 | A failed booking releases its reservation safely, only once | Shreya + Gairik |
| BR6 | Only valid, published ticket types can be booked | Shreya + Gairik |
| BR7 | One payment Idempotency-Key gives at most one charge, even for simultaneous requests | Mudit |
| BR8 | A payment key reused with a different payload is rejected (409) | Mudit |
| BR9 | A booking has at most one in-flight or successful payment | Mudit |

Each service README maps its rules to code and tests.

## Tests and challenge scripts

| What | Java Command (Zero dependencies) | Python Command |
|---|---|---|
| Unit tests (per service) | `cd services/<name> && ./mvnw test` | — |
| End-to-end booking flow | `java scripts/E2EBooking.java` | `python scripts/e2e_booking.py` |
| Booking idempotency (Challenge 2, Booking) | `java scripts/TestBookingIdempotency.java` | `python scripts/test_booking_idempotency.py` |
| Saga with compensation (Challenge 3C) | `java scripts/TestSagaCompensation.java` | `python scripts/test_saga_compensation.py` |
| Concurrency (Challenge 1) | — | `python scripts/test_inventory_concurrency.py` |
| Inventory release / compensation | — | `python scripts/test_inventory_release.py` |
| Payment idempotency (Challenge 2, Payment) | — | `python scripts/test_payment_idempotency.py` |

Evidence for each challenge is in [evidence/](evidence/), with a README per folder linking each file to a claim.

## Repository layout

```
docker-compose.yml   PostgreSQL with one database per service
infra/postgres/      database and user creation script
services/<name>/     source, tests, pom.xml, Maven wrapper, .env.example
scripts/             end-to-end and challenge scripts
docs/                OpenAPI specs, diagrams, report
evidence/            challenge-1-concurrency/, challenge-2-idempotency/, challenge-3c-saga/
```
