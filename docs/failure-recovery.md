# Failure Recovery Walkthrough

This guide is intentionally operational: every result should be captured from the
application metrics, database state, and reconciliation report rather than written
as an estimate.

## Automated entry points

Install the small HTTP/SQL client dependencies first:

```text
python -m pip install -r requirements.txt
```

Run these commands only against a disposable Compose environment. Every mutating
failure script requires `--test-environment`; the drift script also refuses a
database whose `DB_NAME` does not contain `test`.

```text
python scripts/verify_redis_outage_recovery.py --test-environment
python scripts/verify_outbox_crash_recovery.py --test-environment
python scripts/verify_duplicate_settlement.py --test-environment
python scripts/verify_reconciliation_drift_detection.py --test-environment
```

The newest passing report for each scenario is indexed in
`reports/failure-tests/index.md`. Historical failed samples remain in the
directory but are explicitly excluded from the success set.

The benchmark-specific recovery checks use the same disposable environment:

```text
python scripts/benchmark_redis_fault.py --test-environment --clients 200
python scripts/benchmark_settlement_retry.py --test-environment
```

Both commands write raw JSON under `reports/benchmarks/raw/`, assert exactly one
winner or a successful retry, and finish with the five reconciliation invariants.

Set `BASE_URL`, `ADMIN_PASSWORD`, `DB_PASSWORD`, `DB_NAME=campus_errand_test`,
and the matching Compose variables before running. The drift scenario requires
starting Compose with that test database, for example by using a disposable
`.env` file and recreating the stack. Each command writes a JSON report to
`reports/failure-tests/` and exits non-zero when an assertion or cleanup step
fails.

## Redis outage

`verify_redis_outage_recovery.py` stops and restarts the Compose Redis service. It
asserts exactly one HTTP winner, no HTTP 5xx responses, readiness remains `UP`,
the hot order remains `TAKEN`, and the marker plus claim/delivery ZSet entries
are rebuilt after recovery. It then settles both the outage and recovery orders
and runs reconciliation.

## Lost event

`verify_outbox_crash_recovery.py` creates an order, changes its `ORDER_PUBLISHED`
event to expired `PROCESSING`, and waits for the worker to reclaim it. It asserts
the event returns to `PUBLISHED`, the order marker and claim ZSet entry exist,
then cancels the order and runs reconciliation.

## Duplicate settlement

`verify_duplicate_settlement.py` submits concurrent delivery/settlement requests.
It asserts one successful request, business conflicts for the rest, one
`SETTLE:ORDER:{id}` idempotency row, one set of settlement entries, a `SETTLED`
order, a zero ledger sum, and a passing reconciliation.

## Intentional drift

`verify_reconciliation_drift_detection.py` changes one AVAILABLE balance by one
cent in a database explicitly named for testing, calls the admin reconciliation
endpoint, asserts `INV-2` fails, restores the original balance in `finally`, and
asserts the next reconciliation passes. Never run this script against production
data.
