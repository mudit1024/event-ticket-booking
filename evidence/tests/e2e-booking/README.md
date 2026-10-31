# End-to-end test – main booking transaction

Booking validates the event with Catalog, reserves a seat in Inventory, charges Payment and confirms the booking.

Reproduce with all four services running:

```bash
python scripts/e2e_booking.py
```

| Claim | Evidence |
|---|---|
| All four services are up and each reports its own database as UP | `script-output.txt` (health lines) |
| `POST /api/v1/bookings` returns 201 with status `CONFIRMED`, a `reservation_id` and a `payment_id` | `script-output.txt` (booking response) |
| The booking is persisted and readable with `GET /api/v1/bookings/{id}` | `script-output.txt` |
| Inventory drops by exactly the booked quantity (5 → 4) | `script-output.txt`, `db-state.txt` (`inventory-capacity`) |
| The booking, its reservation (`CONFIRMED`) and its payment (`SUCCEEDED`) exist in three separate databases, linked only by id | `db-state.txt` |
| One correlation id follows the request through all four services | `booking-`, `catalog-`, `inventory-` and `payment-service-log.jsonl` (search for the `corr-e2e-...` id) |
