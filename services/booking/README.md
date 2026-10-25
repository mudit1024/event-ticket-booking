# booking

The Booking Orchestration Service. It coordinates the distributed ticket booking lifecycle across Event Catalog, Inventory, and Payment services using an orchestrated Saga with compensating actions. Repeating a request with the same `Idempotency-Key` never duplicates a booking or triggers multiple charges.

**Owner:** Gairik

## Run

```bash
# From repository root: start PostgreSQL (creates booking_db and booking_user)
docker compose up -d

# From this folder
cp .env.example .env          # optional, defaults match docker-compose
./mvnw spring-boot:run        # Windows: mvnw.cmd spring-boot:run
```

Check health: `curl http://localhost:8083/health`. Swagger UI is available at `http://localhost:8083/swagger-ui.html` and the OpenAPI specification is exported to [docs/openapi/booking.yaml](../../docs/openapi/booking.yaml).

## Configuration (Environment Variables)

| Variable | Default | Purpose |
|---|---|---|
| `PORT` | `8083` | HTTP port |
| `DB_URL` | `jdbc:postgresql://localhost:5433/booking_db` | JDBC URL for booking database |
| `DB_USERNAME` / `DB_PASSWORD` | `booking_user` / `booking_pass` | Credentials for this service only |
| `CATALOG_BASE_URL` | `http://localhost:8081` | Downstream Event Catalog service |
| `INVENTORY_BASE_URL` | `http://localhost:8082` | Downstream Inventory service |
| `PAYMENT_BASE_URL` | `http://localhost:8084` | Downstream Payment service |
| `BOOKING_IN_PROGRESS_WAIT_MS` | `5000` | Wait duration for concurrent in-flight requests with same key |

## API Endpoints

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/v1/bookings` | Create and orchestrate a new booking. Requires `Idempotency-Key` header |
| `GET` | `/api/v1/bookings/{id}` | Fetch booking status and identifiers by UUID |
| `POST` | `/api/v1/bookings/{id}/compensate` | Explicitly repeat saga compensation (proves BR5 idempotent release) |
| `GET` | `/health` | Service and database connectivity status |

### Create Booking Request

```http
POST /api/v1/bookings
Idempotency-Key: book-10000000-0000-0000-0000-000000000001-user1
X-Correlation-Id: c89f2a74-9f2b-4d44-a6ec-3982e5b7b9e1
Content-Type: application/json

{
  "event_id": "10000000-0000-0000-0000-000000000001",
  "ticket_type_id": "20000000-0000-0000-0000-000000000001",
  "quantity": 1,
  "customer_email": "alice@example.com"
}
```

### Responses

| Status | Meaning |
|---|---|
| `201` | New booking processed. Ends in `CONFIRMED` or `FAILED` (if payment was declined, triggering compensation) |
| `200` | Replay of previous request with same key and payload. Header `Idempotent-Replay: true` |
| `400` | `MISSING_IDEMPOTENCY_KEY`, `VALIDATION_FAILED`, `MALFORMED_REQUEST`, `EVENT_NOT_FOUND` |
| `409` | `IDEMPOTENCY_KEY_REUSED` (different payload), `INSUFFICIENT_INVENTORY`, `BOOKING_IN_PROGRESS` |

## Booking States & Allowed Transitions

```
           ┌───────────┐
           │  PENDING  │
           └─────┬─────┘
                 │
       ┌─────────┴─────────┐
       ▼                   ▼
┌─────────────┐     ┌────────────┐
│  CONFIRMED  │     │   FAILED   │
└─────────────┘     └────────────┘
```

- Allowed transitions: `PENDING → CONFIRMED` (only after payment succeeds) or `PENDING → FAILED`.
- Terminal states: `CONFIRMED` and `FAILED`.
- Any other transition is rejected with `IllegalStateException`.

## Business Rules

| ID | Rule | Implementation | Test Proof |
|---|---|---|---|
| **BR2** | Same key + same payload creates only one logical booking. | `BookingService.createBooking`, `replay`, `uq_bookings_idempotency_key` constraint | `BookingServiceTest.sameKeySamePayloadReplaysStoredResponse`, `concurrentDuplicateWinsDbRaceAndReplays` |
| **BR3** | Same key + different payload is rejected with 409 conflict. | `BookingService.replay`, `RequestFingerprint.of` | `BookingServiceTest.sameKeyDifferentPayloadRejected` |
| **BR4** | Booking is confirmed only after payment succeeds. Payment decline marks booking `FAILED`. | `BookingService.createBooking` step 3, `Booking.confirm` | `BookingServiceTest.paymentFailureCompensatesReservation` |
| **BR5** | Failed booking releases its reservation safely, only once (Saga compensation). | `BookingService.createBooking` compensation step, `InventoryClient.release` | `BookingServiceTest.paymentFailureCompensatesReservation`, `repeatCompensationIsSafe` |
| **BR6** | Only valid, published ticket types can be booked. | `CatalogClient.validateAndGetTicketType` | `BookingServiceTest.invalidEventRejected` |

## Saga Orchestration & Compensation Flow (Challenge 3C)

1. **Step 1 - Catalog Validation:** Validates event is published and ticket type belongs to event. Calculates total price.
2. **Step 2 - Inventory Reservation:** Calls `POST /api/v1/reservations` with `Idempotency-Key: reserve-<booking_id>`.
3. **Step 3 - Payment Execution:** Calls `POST /api/v1/payments` with `Idempotency-Key: pay-<booking_id>`.
   - **Payment SUCCEEDED:** Calls `POST /api/v1/reservations/{id}/confirm` and transitions booking to `CONFIRMED`.
   - **Payment FAILED (or declined):** Triggers compensating transaction: calls `POST /api/v1/reservations/{id}/release` to restore ticket capacity. Transitions booking to `FAILED`. Repeating compensation is completely safe and idempotent.

## Tests & Scripts

```bash
# Unit & Web-layer tests (15 unit tests, mock-based, no external dependencies needed)
./mvnw test

# End-to-end happy path booking (Pure Java 21)
java ../../scripts/E2EBooking.java

# Challenge 2: Booking Idempotency (Pure Java 21)
java ../../scripts/TestBookingIdempotency.java

# Challenge 3C: Saga Orchestration & Compensation (Pure Java 21)
java ../../scripts/TestSagaCompensation.java
```

*(Python scripts are also provided in `scripts/` as alternative runners if needed).*
