#!/usr/bin/env python3
"""
Integration test run: executes every scenario script against the four running services and
records the evidence for each one.

Before each scenario the inventory is reset to full capacity (scripts/reset_test_inventory.py,
test only), so scenarios are independent of each other and of earlier runs.

For every scenario it writes into the scenario's evidence folder:
  script-output.txt          console output of the script, with the command and timestamps
  <service>-service-log.jsonl  the JSON log lines each service wrote while the scenario ran
  db-state.txt               rows created during the scenario plus the inventory table

Service logs are read from --log-dir/<service>.log. Start each service with its output
redirected there, e.g. from services/booking:  ./mvnw spring-boot:run > ../../logs/booking.log 2>&1

Usage:
  python scripts/run_integration_tests.py [--log-dir logs] [--evidence-dir evidence]
"""
import argparse
import json
import subprocess
import sys
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

import reset_test_inventory

ROOT = Path(__file__).resolve().parent.parent

SERVICES = {  # short name -> (log file name, health url)
    "catalog": ("event-catalog", "http://localhost:8081/health"),
    "inventory": ("inventory", "http://localhost:8082/health"),
    "booking": ("booking", "http://localhost:8083/health"),
    "payment": ("payment-mock", "http://localhost:8084/health"),
}

DATABASES = {
    "booking": ("booking_db", "booking_user", "booking_pass"),
    "inventory": ("inventory_db", "inventory_user", "inventory_pass"),
    "payment": ("payment_db", "payment_user", "payment_pass"),
}

# Rows created during the scenario window ({since} is the scenario start time, UTC).
DB_QUERIES = {
    "booking": "SELECT id, idempotency_key, status, quantity, total_amount, reservation_id, payment_id, failure_reason "
               "FROM bookings WHERE created_at >= '{since}' ORDER BY created_at;",
    "booking-per-key": "SELECT idempotency_key, COUNT(*) AS rows FROM bookings WHERE created_at >= '{since}' "
                       "GROUP BY idempotency_key ORDER BY idempotency_key;",
    "inventory": "SELECT id, booking_id, quantity, status FROM reservations WHERE created_at >= '{since}' ORDER BY created_at;",
    "inventory-capacity": "SELECT ticket_type_id, total_capacity, available_quantity FROM ticket_inventory ORDER BY ticket_type_id;",
    "payment": "SELECT id, booking_id, idempotency_key, status, amount, failure_reason "
               "FROM payments WHERE created_at >= '{since}' ORDER BY created_at;",
}

# (title, evidence folder, file prefix, script, database sections to record)
SCENARIOS = [
    ("Challenge 1: 20 parallel reservations for capacity 5", "challenge-1-concurrency", "",
     "test_inventory_concurrency.py", ["inventory", "inventory-capacity"]),
    ("End-to-end booking (Catalog -> Inventory -> Payment -> CONFIRMED)", "tests/e2e-booking", "",
     "e2e_booking.py", ["booking", "inventory", "inventory-capacity", "payment"]),
    ("Challenge 2: booking idempotency", "challenge-2-idempotency/booking", "",
     "test_booking_idempotency.py", ["booking", "booking-per-key", "inventory", "inventory-capacity", "payment"]),
    ("Challenge 2: payment idempotency", "challenge-2-idempotency/payment", "",
     "test_payment_idempotency.py", ["payment"]),
    ("Challenge 3C: saga compensation on forced payment failure", "challenge-3c-saga", "",
     "test_saga_compensation.py", ["booking", "inventory", "inventory-capacity", "payment"]),
    ("Challenge 3C: repeated release restores capacity once", "challenge-3c-saga", "release-",
     "test_inventory_release.py", ["inventory", "inventory-capacity"]),
]


def now():
    return datetime.now(timezone.utc)


def check_health():
    for name, (_, url) in SERVICES.items():
        try:
            with urllib.request.urlopen(url, timeout=5) as res:
                body = json.loads(res.read().decode())
        except Exception as exc:  # noqa: BLE001 - report any connection problem the same way
            raise SystemExit(f"{name} is not reachable at {url}: {exc}")
        if body.get("status") != "UP":
            raise SystemExit(f"{name} is not healthy: {body}")


def psql(db_key, sql):
    db, user, password = DATABASES[db_key]
    cmd = ["docker", "exec", "-e", f"PGPASSWORD={password}", "etb-postgres",
           "psql", "-h", "localhost", "-U", user, "-d", db, "-c", sql]
    result = subprocess.run(cmd, capture_output=True, text=True)
    return result.stdout if result.returncode == 0 else result.stderr


def log_offsets(log_dir):
    offsets = {}
    for short, (file_name, _) in SERVICES.items():
        path = log_dir / f"{file_name}.log"
        offsets[short] = path.stat().st_size if path.exists() else None
    return offsets


def copy_new_log_lines(log_dir, offsets, folder, prefix):
    saved = []
    for short, start in offsets.items():
        if start is None:
            continue
        path = log_dir / f"{SERVICES[short][0]}.log"
        with path.open("rb") as f:
            f.seek(start)
            lines = [line for line in f.read().decode("utf-8", errors="replace").splitlines()
                     if line.startswith("{")]
        if lines:
            target = folder / f"{prefix}{short}-service-log.jsonl"
            target.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
            saved.append(target.name)
    return saved


def run_scenario(title, folder, prefix, script, db_sections, log_dir):
    folder.mkdir(parents=True, exist_ok=True)
    print(f"\n=== {title}")
    reset_state = reset_test_inventory.reset()
    started = now()
    offsets = log_offsets(log_dir)

    command = [sys.executable, str(ROOT / "scripts" / script)]
    result = subprocess.run(command, capture_output=True, text=True, cwd=ROOT)
    finished = now()
    output = result.stdout + result.stderr
    print(output.rstrip())

    header = (f"# {title}\n"
              f"# command: python scripts/{script}\n"
              f"# started: {started.isoformat(timespec='seconds')}  finished: {finished.isoformat(timespec='seconds')}\n"
              f"# exit code: {result.returncode}\n"
              f"# inventory reset before this run (test only):\n"
              + "".join(f"#   {line}\n" for line in reset_state.strip().splitlines())
              + "\n")
    (folder / f"{prefix}script-output.txt").write_text(header + output, encoding="utf-8", newline="\n")

    since = started.isoformat()
    db_state = [f"# Database state after: {title}", f"# rows created since {since}", ""]
    for section in db_sections:
        db_key = section.split("-")[0]
        db_state.append(f"## {DATABASES[db_key][0]}: {section}")
        db_state.append(psql(db_key, DB_QUERIES[section].format(since=since)))
    (folder / f"{prefix}db-state.txt").write_text("\n".join(db_state), encoding="utf-8", newline="\n")

    logs = copy_new_log_lines(log_dir, offsets, folder, prefix)
    return result.returncode == 0, logs


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--log-dir", default=str(ROOT / "logs"))
    parser.add_argument("--evidence-dir", default=str(ROOT / "evidence"))
    args = parser.parse_args()
    log_dir, evidence_dir = Path(args.log_dir), Path(args.evidence_dir)

    check_health()
    run_started = now()
    results = []
    for title, folder, prefix, script, db_sections in SCENARIOS:
        passed, logs = run_scenario(title, evidence_dir / folder, prefix, script, db_sections, log_dir)
        results.append((title, folder, passed, logs))
    reset_test_inventory.reset()

    lines = [f"Integration test run {run_started.isoformat(timespec='seconds')}", ""]
    for title, folder, passed, logs in results:
        note = "" if logs else "  (no service logs found in --log-dir)"
        lines.append(f"{'PASS' if passed else 'FAIL'}  {title}  -> evidence/{folder}/{note}")
    lines.append("")
    lines.append(f"{sum(r[2] for r in results)}/{len(results)} scenarios passed")
    summary = "\n".join(lines) + "\n"
    (evidence_dir / "integration-run-summary.txt").write_text(summary, encoding="utf-8", newline="\n")
    print("\n" + summary)
    return 0 if all(r[2] for r in results) else 1


if __name__ == "__main__":
    sys.exit(main())
