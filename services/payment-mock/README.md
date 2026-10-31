# payment-mock

A simulated payment provider. It records every payment attempt in its own database (`payment_db`). Repeating a request with the same `Idempotency-Key` never charges twice, and the service can be told to succeed, decline, or respond slowly.

**Owner:** Mudit

## Run

```bash
# from the repository root: start PostgreSQL (creates payment_db / payment_user)
docker compose up -d

# from this folder
cp .env.example .env          # optional, defaults match docker-compose
./mvnw spring-boot:run        # Windows: mvnw.cmd spring-boot:run
```

**Or run it in Docker** (from the repository root). The image is built from this folder's [Dockerfile](Dockerfile), and the container connects to Postgres over the compose network:

```bash
docker compose --profile app up -d --build payment-mock
# host port already in use? PAYMENT_HOST_PORT=18084 docker compose --profile app up -d payment-mock
```

Check that it is up: `curl http://localhost:8084/health`. Swagger UI is at http://localhost:8084/swagger-ui.html and the spec is exported to [docs/openapi/payment-mock.yaml](../../docs/openapi/payment-mock.yaml).

## Configuration (environment variables)

| Variable | Default | Purpose |
|---|---|---|
| `PORT` | `8084` | HTTP port |
| `DB_URL` | `jdbc:postgresql://localhost:5433/payment_db` | JDBC URL |
| `DB_USERNAME` / `DB_PASSWORD` | `payment_user` / `payment_pass` | Credentials for this service only |
| `PAYMENT_MODE` | `SUCCESS` | `SUCCESS`, `FAIL` (decline every charge) or `SLOW` |
| `PAYMENT_DELAY_MS` | `3000` | Delay applied in `SLOW` mode |
| `PAYMENT_IN_PROGRESS_WAIT_MS` | `5000` | How long a duplicate waits for an in-flight payment with the same key |

## API

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/v1/payments` | Charge a booking. Requires the `Idempotency-Key` header |
| `GET` | `/api/v1/payments/{paymentId}` | Fetch a payment |
| `GET` / `PUT` | `/api/v1/admin/simulation` | Read or switch the gateway mode at runtime (demo / tests) |
| `GET` | `/health` | Service and database status |

**Request**

```http
POST /api/v1/payments
Idempotency-Key: pay-<booking_id>
X-Correlation-Id: <propagated from Booking>
Content-Type: application/json

{"booking_id": "b7c1e7a4-...", "amount": 1500.00, "currency": "INR"}
```

**Responses**

| Status | Meaning |
|---|---|
| `201` | New attempt recorded. The body's `status` is `SUCCEEDED` or `FAILED` (a decline is a valid result, not an error) |
| `200` | Replay of an earlier request with the same key and payload. Header `Idempotent-Replay: true` |
| `400` | `VALIDATION_FAILED`, `MALFORMED_REQUEST`, `MISSING_IDEMPOTENCY_KEY` |
| `409` | `IDEMPOTENCY_KEY_REUSED` (different payload), `BOOKING_ALREADY_PAID`, `PAYMENT_IN_PROGRESS` |

```json
{"payment_id": "f4b5...", "booking_id": "b7c1...", "amount": 1500.00, "currency": "INR",
 "status": "FAILED", "failure_reason": "CARD_DECLINED: simulated decline (mode=FAIL)",
 "created_at": "2026-10-24T10:31:23.083Z"}
```

Errors use the common format:

```json
{"timestamp": "...", "status": 409, "error": "Conflict", "code": "IDEMPOTENCY_KEY_REUSED",
 "message": "Idempotency-Key was already used with a different request payload",
 "path": "/api/v1/payments", "correlation_id": "..."}
```

**Switch to failure mode during a demo:**

```bash
curl -X PUT localhost:8084/api/v1/admin/simulation -H "Content-Type: application/json" -d '{"mode":"FAIL"}'
```

## Payment states

`PROCESSING → SUCCEEDED` or `PROCESSING → FAILED`. Both end states are terminal, and [Payment.complete](src/main/java/com/eventticket/payment/domain/Payment.java) rejects any other transition.

## Business rules

| Rule | Code | Test |
|---|---|---|
| BR4 (payment side): a declined charge is stored and reported as `FAILED`, never `SUCCEEDED` | `PaymentService.createPayment`, `PaymentSimulator.charge` | `PaymentServiceTest.declinedChargeIsFailed`, `PaymentSimulatorTest.failModeDeclines` |
| BR7: one Idempotency-Key gives at most one charge; repeats get the stored response, including simultaneous ones | `PaymentService.createPayment` / `replay`, constraint `uq_payments_idempotency_key` | `PaymentServiceTest.sameKeySamePayloadReplays`, `simultaneousDuplicateReplaysWinner`, `duplicateWaitsForInFlightPayment` |
| BR8: the same key with a different payload is rejected with 409 | `PaymentService.replay`, `RequestFingerprint` | `PaymentServiceTest.sameKeyDifferentPayloadRejected`, `RequestFingerprintTest` |
| BR9: a booking has at most one in-flight or successful payment | partial unique index `uq_payments_booking_active` | `PaymentServiceTest.secondKeyForPaidBookingRejected` |

## Tests

```bash
./mvnw test                                       # unit + web-layer tests, no database needed
python ../../scripts/test_payment_idempotency.py  # live checks against the running service + DB
```

**Postman:** import [docs/postman/payment-mock.postman_collection.json](../../docs/postman/payment-mock.postman_collection.json), set the `base_url` collection variable to the service's address, and run the folders in order (or the whole collection in the Collection Runner). Every request has assertions. The same collection runs from the command line with `npx newman run docs/postman/payment-mock.postman_collection.json`.

The `payment-mock` GitHub Actions workflow runs `./mvnw test` and builds the Docker image on every push or pull request that touches this folder. The latest local test run is saved in [evidence/tests/payment-mock-unit-tests.txt](../../evidence/tests/payment-mock-unit-tests.txt).
