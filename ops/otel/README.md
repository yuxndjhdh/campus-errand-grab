# OpenTelemetry Boundary

The application currently emits an HTTP `X-Trace-Id` and MDC field. This is useful for local correlation but is not a distributed OpenTelemetry trace.

The production follow-up should deploy an OpenTelemetry Java agent and Collector with spans for HTTP, JDBC, Redis, Outbox publishing, and settlement retry. The collector must redact Authorization headers, passwords, JWTs, and request bodies before export.

Until the agent and a trace backend are deployed, `reports/observability/trace-coverage.json` must remain marked `NOT_RUN`; the existing trace ID must not be presented as a 95% OpenTelemetry coverage result.
