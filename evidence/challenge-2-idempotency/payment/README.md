# Challenge 2 – Idempotency (Payment service)

The Payment service must never charge twice for the same logical payment. Booking sends an
`Idempotency-Key` with every `POST /api/v1/payments`; this folder shows what happens when that request is repeated.

Reproduce with the payment service running on port 8084:

```bash
python scripts/test_payment_idempotency.py
```

| Claim | Evidence |
|---|---|
| Same key + same payload returns the stored response (200, `Idempotent-Replay: true`) with the same `payment_id` | `script-output.txt` – scenario 1 (`first` vs `repeat` lines) |
| Same key + different payload is rejected with 409 `IDEMPOTENCY_KEY_REUSED` | `script-output.txt` – scenario 2 |
| 20 simultaneous identical requests → exactly one 201, nineteen 200 replays, one payment id | `script-output.txt` – scenario 3 |
| Duplicates arriving while the first charge is still processing (SLOW mode, 2 s) wait and replay it | `script-output.txt` – scenario 4 |
| A second key for an already-paid booking is rejected with 409 `BOOKING_ALREADY_PAID` | `script-output.txt` – scenario 5 |
| A declined payment is stored as FAILED and replays as FAILED; a new key after a decline can succeed | `script-output.txt` – scenario 6 |
| Database holds exactly one row per idempotency key | `script-output.txt` – scenario 7 (psql output), `db-state.txt` |
| Logs show the replays and the race being resolved, each line carrying the caller's correlation id | `payment-service-log.jsonl` (`idempotent replay`, `concurrent duplicate detected`) |

## How it works

1. The service computes a SHA-256 fingerprint of the canonical payload (`booking_id|amount|currency`).
2. It inserts a `PROCESSING` row **before** calling the simulated gateway. The unique constraint
   `uq_payments_idempotency_key` lets the database pick a single winner when requests arrive together.
3. The losing request catches the constraint violation, reads the winner's row, compares fingerprints
   and returns the stored `response_body`, waiting up to `PAYMENT_IN_PROGRESS_WAIT_MS` if the winner is still processing.
4. A partial unique index `uq_payments_booking_active (booking_id) WHERE status IN ('PROCESSING','SUCCEEDED')`
   blocks a second successful charge for the same booking even when a different key is used.

**Why check-then-insert in application code is not enough:** two requests can both run
`SELECT ... WHERE idempotency_key = ?`, both see no row, and both charge. Only a database constraint
makes "claim the key" atomic.

## Limitations

- If the service crashes between claiming the key and completing the charge, the row stays `PROCESSING`
  and duplicates get 409 `PAYMENT_IN_PROGRESS`. A real system would add a recovery job that reconciles with the gateway.
- Keys are kept forever; a production system would expire them after a retention window.
