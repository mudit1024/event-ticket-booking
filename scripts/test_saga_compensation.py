#!/usr/bin/env python3
"""
Saga Orchestration & Compensation Validation (Challenge 3C).

Saga Pass Condition:
  Force payment failure at step 3.
  Booking ends FAILED.
  Reservation is released via compensating transaction.
  Inventory returns to the prior expected value.
  Repeating compensation leaves the database consistent (harmless idempotent release).

Usage:
  python scripts/test_saga_compensation.py [--booking-url http://localhost:8083]
Uses only the standard library.
"""
import argparse
import json
import sys
import urllib.error
import urllib.request
import uuid
from datetime import datetime

RESULTS = []
EVENT_ID = "10000000-0000-0000-0000-000000000001"
TICKET_TYPE_ID = "20000000-0000-0000-0000-000000000001"


def log(msg):
    print(f"[{datetime.now().isoformat(timespec='milliseconds')}] {msg}", flush=True)


def call(method, url, body=None, headers=None):
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(url, data=data, method=method)
    request.add_header("Content-Type", "application/json")
    for k, v in (headers or {}).items():
        request.add_header(k, v)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.status, dict(response.headers), json.loads(response.read() or b"null")
    except urllib.error.HTTPError as error:
        return error.code, dict(error.headers), json.loads(error.read() or b"null")


def check(name, condition, detail=""):
    RESULTS.append((name, condition))
    log(f"{'PASS' if condition else 'FAIL'}  {name}  {detail}")


def set_payment_mode(payment_url, mode):
    status, _, body = call("PUT", f"{payment_url}/api/v1/admin/simulation", {"mode": mode})
    assert status == 200, f"Failed to set payment mode to {mode}: status {status}"
    log(f"Payment gateway simulation mode switched to: {mode}")


def main():
    parser = argparse.ArgumentParser(description="Saga compensation test suite")
    parser.add_argument("--booking-url", default="http://localhost:8083")
    parser.add_argument("--inventory-url", default="http://localhost:8082")
    parser.add_argument("--payment-url", default="http://localhost:8084")
    args = parser.parse_args()

    log("=== Starting Challenge 3C: Saga Orchestration & Compensation Validation ===")

    # 1. Verify services health
    for name, url in [("Inventory", args.inventory_url),
                      ("Booking", args.booking_url),
                      ("Payment", args.payment_url)]:
        s, _, body = call("GET", f"{url}/health")
        if s != 200:
            log(f"Service {name} is DOWN (status {s})")
            return 1
        log(f"Service {name} is UP: {body}")

    try:
        # 2. Record initial inventory
        s, _, inv_init = call("GET", f"{args.inventory_url}/api/v1/inventory/{TICKET_TYPE_ID}")
        assert s == 200
        avail_init = inv_init.get("available_quantity")
        log(f"Initial available inventory: {avail_init}")

        # 3. Force payment failure mode on Payment service (simulate decline)
        set_payment_mode(args.payment_url, "FAIL")

        # 4. Initiate booking (which will fail at payment and trigger Saga compensation)
        key = f"saga-fail-{uuid.uuid4().hex[:10]}"
        payload = {
            "event_id": EVENT_ID,
            "ticket_type_id": TICKET_TYPE_ID,
            "quantity": 1,
            "customer_email": "saga_test@example.com"
        }
        log(f"Triggering booking with key {key} while payment mode is FAIL...")
        s_book, _, b_book = call("POST", f"{args.booking_url}/api/v1/bookings", payload, {"Idempotency-Key": key})

        log(f"Booking response: status_code={s_book}, body={json.dumps(b_book, indent=2)}")
        booking_id = b_book.get("booking_id")
        reservation_id = b_book.get("reservation_id")

        # BR4: Booking must end in FAILED
        check("booking_status_failed", b_book.get("status") == "FAILED", f"status={b_book.get('status')}")
        check("booking_has_failure_reason", bool(b_book.get("failure_reason")), f"reason={b_book.get('failure_reason')}")
        check("booking_has_reservation_id", bool(reservation_id), f"reservation_id={reservation_id}")

        # 5. Check inventory capacity is restored to initial value (Saga compensation restored hold)
        s, _, inv_restored = call("GET", f"{args.inventory_url}/api/v1/inventory/{TICKET_TYPE_ID}")
        avail_restored = inv_restored.get("available_quantity")
        log(f"Inventory after saga compensation: {avail_restored} (initial: {avail_init})")
        check("inventory_restored_to_initial", avail_restored == avail_init,
              f"{avail_restored} == {avail_init}")

        # 6. Check reservation state in Inventory Service: must be RELEASED
        if reservation_id:
            s_res, _, res_body = call("GET", f"{args.inventory_url}/api/v1/reservations/{reservation_id}")
            check("reservation_status_released", res_body.get("status") == "RELEASED",
                  f"reservation status={res_body.get('status')}")

        # 7. Repeat compensation: calling compensation again is harmless and idempotent (BR5)
        log("Testing repeat compensation invocation (BR5 idempotent release)...")
        s_comp, _, b_comp = call("POST", f"{args.booking_url}/api/v1/bookings/{booking_id}/compensate")
        check("repeat_compensation_success", s_comp == 200, f"status={s_comp}")

        # Verify inventory remains unchanged after second release
        s, _, inv_after_second = call("GET", f"{args.inventory_url}/api/v1/inventory/{TICKET_TYPE_ID}")
        avail_after_second = inv_after_second.get("available_quantity")
        log(f"Inventory after repeated compensation: {avail_after_second} (must still be {avail_init})")
        check("repeat_compensation_leaves_inventory_consistent", avail_after_second == avail_init,
              f"{avail_after_second} == {avail_init}")

    finally:
        # Reset payment mode to SUCCESS
        log("Resetting payment mode to SUCCESS...")
        set_payment_mode(args.payment_url, "SUCCESS")

    passed = sum(1 for _, ok in RESULTS if ok)
    failed = sum(1 for _, ok in RESULTS if not ok)
    log(f"=== SUMMARY: {passed} PASSED, {failed} FAILED ===")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
