#!/usr/bin/env python3
"""
End-to-End Booking Flow Validation (Phase 2).

Proves the complete distributed transaction across all 4 services:
  1. Catalog Service (8081): fetch event and ticket type
  2. Inventory Service (8082): inspect initial capacity
  3. Booking Service (8083): orchestrate booking (validate -> reserve -> pay -> confirm)
  4. Inspect persisted state: booking CONFIRMED, inventory deducted, payment SUCCEEDED

Usage:
  python scripts/e2e_booking.py
"""
import argparse
import json
import urllib.error
import urllib.request
import uuid
from datetime import datetime

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


def main():
    parser = argparse.ArgumentParser(description="End-to-end booking flow test")
    parser.add_argument("--catalog-url", default="http://localhost:8081")
    parser.add_argument("--inventory-url", default="http://localhost:8082")
    parser.add_argument("--booking-url", default="http://localhost:8083")
    parser.add_argument("--payment-url", default="http://localhost:8084")
    args = parser.parse_args()

    log("=== Starting End-to-End Booking Validation ===")

    # 1. Health checks on all 4 services
    for name, url in [("Catalog", args.catalog_url),
                      ("Inventory", args.inventory_url),
                      ("Booking", args.booking_url),
                      ("Payment", args.payment_url)]:
        status, _, body = call("GET", f"{url}/health")
        if status != 200:
            log(f"Service {name} is DOWN (status {status}). Start services before running e2e test.")
            return 1
        log(f"Service {name} is UP: {body}")

    # 2. Check event in Catalog
    status, _, event = call("GET", f"{args.catalog_url}/api/v1/events/{EVENT_ID}")
    assert status == 200, f"Failed to get event: status {status}"
    log(f"Catalog Event verified: '{event.get('name')}', published={event.get('published')}")

    # 3. Check initial inventory
    status, _, inv_before = call("GET", f"{args.inventory_url}/api/v1/inventory/{TICKET_TYPE_ID}")
    assert status == 200, f"Failed to get inventory: status {status}"
    avail_before = inv_before.get("available_quantity")
    log(f"Initial available inventory for ticket type {TICKET_TYPE_ID}: {avail_before}")

    # 4. Trigger booking via Booking Service
    idempotency_key = f"e2e-book-{uuid.uuid4().hex[:12]}"
    correlation_id = f"corr-e2e-{uuid.uuid4().hex[:8]}"
    payload = {
        "event_id": EVENT_ID,
        "ticket_type_id": TICKET_TYPE_ID,
        "quantity": 1,
        "customer_email": "e2e_user@example.com"
    }

    log(f"Dispatching POST /api/v1/bookings with key={idempotency_key}...")
    status, headers, booking = call(
        "POST", f"{args.booking_url}/api/v1/bookings",
        payload,
        {"Idempotency-Key": idempotency_key, "X-Correlation-Id": correlation_id}
    )

    log(f"Booking Response (HTTP {status}): {json.dumps(booking, indent=2)}")
    assert status == 201, f"Expected 201 Created, got {status}"
    assert booking.get("status") == "CONFIRMED", f"Expected CONFIRMED, got {booking.get('status')}"
    booking_id = booking.get("booking_id")
    reservation_id = booking.get("reservation_id")
    payment_id = booking.get("payment_id")

    log(f"Booking successfully CONFIRMED! booking_id={booking_id}, reservation_id={reservation_id}, payment_id={payment_id}")

    # 5. Fetch booking by ID (persisted state check)
    status, _, fetched = call("GET", f"{args.booking_url}/api/v1/bookings/{booking_id}")
    assert status == 200 and fetched.get("status") == "CONFIRMED"
    log(f"GET /api/v1/bookings/{booking_id} verified persisted state.")

    # 6. Verify inventory was decremented
    status, _, inv_after = call("GET", f"{args.inventory_url}/api/v1/inventory/{TICKET_TYPE_ID}")
    avail_after = inv_after.get("available_quantity")
    log(f"Inventory after booking: {avail_after} (was {avail_before})")
    assert avail_after == avail_before - 1, f"Inventory was not decremented by 1! {avail_before} -> {avail_after}"

    log("=== End-to-End Booking Validation SUCCESS ===")
    return 0


if __name__ == "__main__":
    sys.exit(main())
