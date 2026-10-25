#!/usr/bin/env python3
"""
Booking Idempotency Validation (Challenge 2, Booking Service).

Scenarios:
  1. Sequential duplicate     - same key + same payload -> 201 then 200 replay with header Idempotent-Replay: true
  2. Conflicting payload      - same key + different payload -> 409 IDEMPOTENCY_KEY_REUSED
  3. Simultaneous duplicates  - N parallel requests with same key -> exactly one 201 Created, rest 200 OK
  4. Missing Idempotency-Key  - 400 MISSING_IDEMPOTENCY_KEY
  5. Single logical booking   - All parallel duplicates return identical booking_id; DB holds 1 record

Usage:
  python scripts/test_booking_idempotency.py [--booking-url http://localhost:8083] [--parallel 20]
Uses only the standard library.
"""
import argparse
import json
import subprocess
import sys
import urllib.error
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor
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


def book(base, key, event_id=EVENT_ID, ticket_type_id=TICKET_TYPE_ID, quantity=1, email="test@example.com"):
    headers = {"Idempotency-Key": key} if key is not None else {}
    headers["X-Correlation-Id"] = f"idem-{uuid.uuid4().hex[:8]}"
    payload = {
        "event_id": event_id,
        "ticket_type_id": ticket_type_id,
        "quantity": quantity,
        "customer_email": email
    }
    return call("POST", f"{base}/api/v1/bookings", payload, headers)


def count_db_rows(key):
    cmd = [
        "docker", "exec", "etb-postgres",
        "psql", "-U", "booking_user", "-d", "booking_db", "-t", "-A", "-c",
        f"SELECT count(*) FROM bookings WHERE idempotency_key = '{key}';"
    ]
    try:
        out = subprocess.check_output(cmd, stderr=subprocess.DEVNULL, timeout=5)
        return int(out.decode().strip())
    except Exception:
        return None


def main():
    parser = argparse.ArgumentParser(description="Booking idempotency test suite")
    parser.add_argument("--booking-url", default="http://localhost:8083")
    parser.add_argument("--parallel", type=int, default=20)
    args = parser.parse_args()

    log(f"=== Booking Idempotency Validation (Challenge 2) targeting {args.booking_url} ===")

    # 1. Health check
    status, _, health = call("GET", f"{args.booking_url}/health")
    if status != 200:
        log(f"Booking service health check failed (status {status})")
        return 1
    log(f"Booking service is UP: {health}")

    # 2. Sequential duplicate test (BR2)
    key_seq = f"book-seq-{uuid.uuid4().hex[:10]}"
    log(f"Scenario 1: Sequential duplicate with key {key_seq}")
    s1, h1, b1 = book(args.booking_url, key_seq, quantity=1, email="alice@example.com")
    check("sequential_first_call_201", s1 == 201, f"status={s1}")
    check("sequential_first_call_not_replayed", h1.get("Idempotent-Replay") == "false")

    s2, h2, b2 = book(args.booking_url, key_seq, quantity=1, email="alice@example.com")
    check("sequential_second_call_200", s2 == 200, f"status={s2}")
    check("sequential_second_call_replayed", h2.get("Idempotent-Replay") == "true")
    check("sequential_same_booking_id", b1.get("booking_id") == b2.get("booking_id"),
          f"{b1.get('booking_id')} == {b2.get('booking_id')}")

    # 3. Payload conflict test (BR3)
    log(f"Scenario 2: Payload conflict with same key {key_seq}")
    s3, _, b3 = book(args.booking_url, key_seq, quantity=2, email="alice@example.com")  # changed quantity
    check("payload_conflict_409", s3 == 409, f"status={s3}")
    check("payload_conflict_error_code", b3.get("code") == "IDEMPOTENCY_KEY_REUSED", f"code={b3.get('code')}")

    # 4. Missing Idempotency-Key header test
    log("Scenario 3: Missing Idempotency-Key header")
    s_miss, _, b_miss = book(args.booking_url, None)
    check("missing_header_400", s_miss == 400, f"status={s_miss}")
    check("missing_header_code", b_miss.get("code") == "MISSING_IDEMPOTENCY_KEY", f"code={b_miss.get('code')}")

    # 5. Simultaneous duplicates test (BR2 concurrency race protection)
    key_par = f"book-simul-{uuid.uuid4().hex[:10]}"
    log(f"Scenario 4: Simultaneous duplicate race ({args.parallel} parallel requests) with key {key_par}")

    def submit_req(idx):
        return book(args.booking_url, key_par, quantity=1, email="concurrency@example.com")

    with ThreadPoolExecutor(max_workers=args.parallel) as executor:
        responses = list(executor.map(submit_req, range(args.parallel)))

    status_counts = {}
    booking_ids = set()
    for s, h, b in responses:
        status_counts[s] = status_counts.get(s, 0) + 1
        if b and "booking_id" in b:
            booking_ids.add(b["booking_id"])

    log(f"Simultaneous duplicate results: status_counts={status_counts}, unique booking_ids={len(booking_ids)}")
    check("simultaneous_exactly_one_201", status_counts.get(201) == 1, f"201 count={status_counts.get(201)}")
    check("simultaneous_rest_are_200", status_counts.get(200) == args.parallel - 1, f"200 count={status_counts.get(200)}")
    check("simultaneous_single_logical_booking", len(booking_ids) == 1, f"booking_ids={booking_ids}")

    # 6. Database uniqueness check
    db_count = count_db_rows(key_par)
    if db_count is not None:
        check("database_single_row", db_count == 1, f"row_count={db_count}")
    else:
        log("SKIP database row count check (etb-postgres not reachable directly)")

    # Summary
    passed = sum(1 for _, ok in RESULTS if ok)
    failed = sum(1 for _, ok in RESULTS if not ok)
    log(f"=== SUMMARY: {passed} PASSED, {failed} FAILED ===")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
