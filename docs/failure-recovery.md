# Failure Recovery Walkthrough

This guide is intentionally operational: every result should be captured from the
application metrics, database state, and reconciliation report rather than written
as an estimate.

## Redis outage

1. Start the stack with `docker compose up --build` and run `scripts/smoke_curl.sh`.
2. Stop Redis with `docker compose stop redis`.
3. Run `python scripts/load_test.py --phase burst --clients 200`.
4. Verify that exactly one request wins, the order is still `TAKEN`, and MySQL CAS
   counters increase while Redis-degraded logs are emitted.
5. Start Redis again and verify the marker and `delay:deliver` member are recreated
   by the Outbox Worker or `MarkerResyncJob`.

## Lost event

Create an order, stop the app immediately after the HTTP response, and inspect
`t_outbox_event`. Restart the app and verify that `ORDER_PUBLISHED` or
`ORDER_TAKEN` moves to `PUBLISHED` and the Redis marker/ZSet entry exists.

## Duplicate settlement

Call the delivery endpoint, then run the settlement retry test or submit concurrent
settlement calls. The order must be `SETTLED`, the `SETTLE:ORDER:{id}` idempotency
row must occur once, and the ledger must still sum to zero.

## Intentional drift

For a local demonstration only, change an account balance with SQL, call the admin
reconciliation endpoint, and verify `INV-2` fails. Restore the database from the
test fixture before the next run; do not run this step against production data.
