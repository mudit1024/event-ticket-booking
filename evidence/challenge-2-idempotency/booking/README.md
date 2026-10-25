# Challenge 2 – Idempotency (Booking service)

**Owner:** Gairik

The Booking service orchestrates multiple downstream services (Catalog, Inventory, Payment). If a client retries `POST /api/v1/bookings`, the system must never create a second booking, reserve extra tickets, or charge the customer twice.

Reproduce with all services running:

```bash
python scripts/test_booking_idempotency.py
```

| Claim | Evidence |
|---|---|
| Same key + same payload returns the stored response (HTTP 200, header `Idempotent-Replay: true`) with the exact same `booking_id` | Scenario 1 (`sequential_first_call_201`, `sequential_second_call_200`, `sequential_same_booking_id`) |
| Same key + different payload is rejected with HTTP 409 `IDEMPOTENCY_KEY_REUSED` | Scenario 2 (`payload_conflict_409`, `payload_conflict_error_code`) |
| Missing `Idempotency-Key` header is rejected with HTTP 400 `MISSING_IDEMPOTENCY_KEY` | Scenario 3 (`missing_header_400`, `missing_header_code`) |
| 20 simultaneous parallel requests with the same key → exactly one HTTP 201 Created, 19 HTTP 200 replays, single `booking_id` | Scenario 4 (`simultaneous_exactly_one_201`, `simultaneous_rest_are_200`, `simultaneous_single_logical_booking`) |
| Database uniqueness constraint ensures exactly one row is stored in `bookings` table for that key | Scenario 5 (`database_single_row` via psql) |

## How it Works

1. The service canonicalizes the booking payload and generates a SHA-256 fingerprint:
   `sha256(event_id|ticket_type_id|quantity|customer_email)`.
2. It claims the idempotency key durably in the database by inserting a row in status `PENDING` with a unique constraint `uq_bookings_idempotency_key`.
3. If two concurrent requests arrive simultaneously, PostgreSQL enforces atomicity: one succeeds, and the other hits `DataIntegrityViolationException`.
4. The loser queries the winner's row, verifies the fingerprint, awaits completion if the winner is still in-flight, and returns the stored `response_body` with `Idempotent-Replay: true`.
5. Downstream calls (to Inventory and Payment) use derivative idempotency keys `reserve-<booking_id>` and `pay-<booking_id>`, guaranteeing that even if a retry propagates downstream, downstream services also replay safely.

## Why Check-Then-Insert in Code Fails

In a high-concurrency distributed environment, two simultaneous requests can both execute `SELECT ... WHERE idempotency_key = ?`, observe no existing record, and both proceed to reserve tickets and charge cards. Only a database-level uniqueness constraint provides true race protection.
