# Campus Errand SLO Draft

This is the first local SLO contract. It is a measurement definition, not a production SLA.

| Service indicator | Window | Initial objective | Source |
| --- | --- | ---: | --- |
| Grab HTTP 5xx ratio | 5 minutes | < 5% | `http_server_requests_seconds_count` |
| Grab P99 latency | per benchmark variant | record baseline before setting a release gate | load-test JSON |
| Single-winner correctness | per order | 1 winner | `winnerInvariantPassed` and database query |
| Database CAS reduction | per Redis A/B pair | retain the formal baseline or explain regression | `grab_db_cas_total` |
| Settlement retry backlog | 5 minutes | no unbounded growth | `settlement_retry_total`, order state |
| Outbox DEAD events | instant | 0 | `outbox_dead` |
| Reconciliation failures | 5 minutes | 0 | `recon_failure_total` |
| Timeout queue lag | instant | <= 30 seconds | `timeout_queue_lag_seconds` |

## Measurement Rules

- Business rejections such as 409 and 429 are not HTTP 5xx system errors.
- A performance result is valid only when HTTP 5xx, single-winner, database CAS, and all five financial invariants pass together.
- Windows Docker observations are not production capacity or availability claims.
- Every release report must include the test window, sample count, failed samples, and the raw JSON path.

## Alert Mapping

Runbooks for the current alert rules live under `ops/runbooks/`. The local monitoring validation currently proves rule firing and recovery. Delivery to an external notification system requires an Alertmanager/receiver deployment and is tracked separately in `reports/observability/alert-routing-validation.json`.
