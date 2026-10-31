# Challenge 1 – Concurrency control (Inventory service)

**Owner:** Shreya

General Admission (`20000000-0000-0000-0000-000000000001`) has capacity 5. Twenty reservation requests for it are fired at the same moment; exactly five may succeed.

Reproduce with the Inventory service running (the integration runner resets capacity to 5 first):

```bash
python scripts/run_integration_tests.py      # all scenarios, records this folder
python scripts/test_inventory_concurrency.py # this scenario only, needs a fresh capacity of 5
```

| Claim | Evidence |
|---|---|
| 20 parallel requests → exactly 5 × 201, 15 × 409 `INSUFFICIENT_INVENTORY`, no other errors | `script-output.txt` (summary counts and `PASS` line) |
| Inventory never goes negative: final `available_quantity` is 0 of 5 | `script-output.txt` (final inventory), `db-state.txt` (`inventory-capacity`) |
| Exactly five reservation rows exist, all `RESERVED` | `db-state.txt` (`inventory` section) |
| Each request was handled independently and the rejected ones were refused by the database update, not by a Java check | `inventory-service-log.jsonl` (`request completed` for the 20 POSTs: 5 × 201, 15 × 409, each with its own correlation id, plus the two GET snapshots before and after) |

## How it works

Capacity is taken with one atomic conditional update ([InventoryRepository.decrementCapacity](../../services/inventory/src/main/java/com/eventticket/inventory/repository/InventoryRepository.java)):

```sql
UPDATE ticket_inventory
SET available_quantity = available_quantity - :qty
WHERE ticket_type_id = :id AND event_id = :event AND available_quantity >= :qty
```

PostgreSQL locks the row while it runs the update, so concurrent requests are applied one at a time. When the seats are gone the `WHERE` no longer matches, the update changes 0 rows, and the service answers 409. The table also has `CHECK (available_quantity >= 0)` as a last line of defence.

## Why check-then-update in application code fails

With `SELECT available_quantity` followed by `UPDATE ... SET available_quantity = <value computed in Java>`, several requests read "1 left" before any of them writes. All of them pass the check and all of them reserve, so the event is oversold, and the last write overwrites the others so the count is wrong as well. Doing the check and the decrement in one statement removes that gap.

## Limitations

- Reservations are held until Booking confirms or releases them; there is no time-based expiry (that would be Challenge 3B).
- All requests contend on one row per ticket type, which is fine at this scale but would become a hotspot for very popular events.
