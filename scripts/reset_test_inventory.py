#!/usr/bin/env python3
"""
TEST ONLY: put every ticket type back to full capacity before an independent test scenario.

Confirmed bookings keep their seats, so after a few runs the demo ticket types are sold out and
later scripts fail for lack of capacity rather than because of a bug. This script deletes all
reservations in inventory_db and sets available_quantity = total_capacity.

It talks to the local PostgreSQL container directly and is not part of any service, so the
booking flow itself is unchanged. Never run it against data you want to keep.

Usage:
  python scripts/reset_test_inventory.py --yes
"""
import argparse
import subprocess
import sys

CONTAINER = "etb-postgres"
RESET_SQL = """
BEGIN;
DELETE FROM reservations;
UPDATE ticket_inventory SET available_quantity = total_capacity, updated_at = CURRENT_TIMESTAMP;
COMMIT;
"""
STATE_SQL = "SELECT ticket_type_id, total_capacity, available_quantity FROM ticket_inventory ORDER BY ticket_type_id;"


def psql(sql):
    cmd = ["docker", "exec", "-e", "PGPASSWORD=inventory_pass", CONTAINER,
           "psql", "-h", "localhost", "-U", "inventory_user", "-d", "inventory_db", "-v", "ON_ERROR_STOP=1", "-c", sql]
    return subprocess.run(cmd, capture_output=True, text=True, check=True).stdout


def reset():
    psql(RESET_SQL)
    return psql(STATE_SQL)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--yes", action="store_true", help="confirm that local test reservations may be deleted")
    args = parser.parse_args()
    if not args.yes:
        print("Refusing to reset without --yes (this deletes every reservation in inventory_db).")
        return 2
    try:
        print("Inventory reset to full capacity (test only):")
        print(reset())
    except (subprocess.CalledProcessError, FileNotFoundError) as exc:
        print(f"Reset failed: {getattr(exc, 'stderr', exc)}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
