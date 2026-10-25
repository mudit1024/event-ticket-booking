# Challenge 3C – Saga with Compensation

**Owner:** Gairik (Orchestrator) & Shreya (Inventory Compensation) & Mudit (Payment Failure Injection)

In a distributed microservice architecture without two-phase commits, a failure in a multi-step transaction must be reversed cleanly using compensating transactions to guarantee eventual consistency.

## Scenario: Step 3 Failure (Forced Payment Decline)

The booking transaction proceeds in three explicit steps:
1. **Catalog Validation:** Verifies event and ticket type exist, are published, and calculates total price.
2. **Inventory Reservation:** Creates a temporary atomic reservation (`POST /api/v1/reservations`), deducting ticket quantity from available inventory.
3. **Payment Execution:** Calls Payment Service (`POST /api/v1/payments`).

When payment fails (e.g., simulated card decline when `PAYMENT_MODE=FAIL`):
- Booking Service detects payment failure and **does not** mark the booking confirmed.
- **Compensating Action:** Booking Service calls `POST /api/v1/reservations/{reservation_id}/release` to release the reserved tickets and restore inventory capacity.
- Booking transitions to terminal state `FAILED` with `failure_reason`.
- Repeating compensation (`POST /api/v1/bookings/{id}/compensate`) is safe and idempotent: Inventory Service does not increment available stock twice.

## Reproduction

Run the automated Saga compensation script:

```bash
python scripts/test_saga_compensation.py
```

## Verification & Claims

| Rule / Claim | Verification | Result |
|---|---|---|
| **BR4: No unearned confirmation** | When payment fails, booking status ends in `FAILED` with failure details, never `CONFIRMED` | Verified (`booking_status_failed`) |
| **BR5: Automatic compensation** | Failed booking triggers reservation release in Inventory; inventory availability returns to initial value | Verified (`inventory_restored_to_initial`, `reservation_status_released`) |
| **BR5: Repeat compensation safe** | Triggering compensation a second time (`POST /api/v1/bookings/{id}/compensate`) returns success and does not increase inventory twice | Verified (`repeat_compensation_leaves_inventory_consistent`) |
| **BR6: Event validation barrier** | Non-existent or unpublished events fail at Step 1 before any reservation or payment attempt | Verified by unit & integration tests |

## Traceability & Log Correlation

Every distributed request carries `X-Correlation-Id` across services. When inspecting logs, the entire flow (Catalog GET → Inventory POST reserve → Payment POST pay → Inventory POST release) can be traced under a single correlation ID in the structured JSON logs.
