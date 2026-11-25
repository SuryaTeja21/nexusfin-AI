# ADR-008: Full-Stack Observability with Prometheus, Grafana Tempo, OpenTelemetry & Structured JSON Logging

## Status
**Accepted**

## Context & Problem Statement
As NexusFin AI scaled across five independent Spring Boot 3.2 microservices (`discovery-server`, `api-gateway`, `ledger-service`, `ai-fraud-service`, and `notification-service`) backed by asynchronous transactional outbox polling, Apache Kafka, Redis, and PostgreSQL, operational debugging and latency analysis revealed critical observability blind spots:
1. Metrics were only partially exposed (`/actuator/metrics`), lacking Prometheus scrape targets, HTTP latency histogram buckets (`p95` / `p99`), and outbox queue depth visibility.
2. `api-gateway` generated an `X-Correlation-Id` header on edge ingress, but downstream services, async `OutboxPublisherService` threads, and Kafka consumers did not share a unified distributed trace context or MDC correlation field.
3. Unstructured plain-text console logs could not be reliably indexed or queried by `traceId`, `spanId`, `X-Correlation-Id`, or `service`.

## Decision Drivers
* Standardized Prometheus scraping (`/actuator/prometheus`) across every microservice with percentile histograms enabled for `p95`/`p99` SLA monitoring.
* End-to-end distributed tracing spanning reactive WebFlux (`api-gateway`), Spring MVC (`ledger-service`, `ai-fraud-service`), transactional outbox relays, and Kafka consumers/producers (`notification-service`).
* Seamless propagation of existing `X-Correlation-Id` across HTTP headers, OpenTelemetry W3C `baggage`, Reactor Netty threads, PostgreSQL outbox rows, Kafka record headers, and SLF4J MDC.
* Structured JSON console logs ready for centralized log aggregation (Loki / ELK / CloudWatch).
* Isolated, memory-bounded observability containers in `docker-compose.observability.yml` so local core development remains lean.

## Architecture Overview

```text
Client (X-Correlation-Id)
  │
  ▼
[api-gateway :8080] (WebFlux + ReactorBaggage + ObservationAwareBaggageThreadLocalAccessor)
  │  injects traceparent + baggage (X-Correlation-Id) + HTTP header
  ├──► [ledger-service :8081] (CorrelationIdMdcFilter -> MDC + OutboxEvent.trace_parent / correlation_id)
  │       │
  │       ▼ (async SKIP LOCKED poll)
  │    [OutboxPublisherService] (extracts trace_parent -> child span "outbox.dispatch")
  │       │
  │       ▼ (KafkaTemplate observation-enabled -> traceparent + baggage headers)
  │    [Apache Kafka :9092]
  │       ├──► [ai-fraud-service :8082] (Kafka listener observation -> MDC + traceId)
  │       └──► [notification-service :8084] (Kafka listener observation -> MDC + traceId)
  │
  ▼ OTLP HTTP (:4318)                 ▲ Scrapes /actuator/prometheus every 5s
[Grafana Tempo :3200] ◄── [Grafana :3000] ──► [Prometheus :9090]
```

## Decision Outcome
1. **Prometheus Metrics on All Services**:
   - Added `io.micrometer:micrometer-registry-prometheus` across all modules via parent `pom.xml`.
   - Exposed `/actuator/prometheus` (permitted in `SecurityConfig` on `api-gateway`, `ledger-service`, and `notification-service`) with `management.metrics.distribution.percentiles-histogram.http.server.requests=true`.
   - Instrumented `outbox.pending.events` (`OutboxMetricsConfig` gauge in `ledger-service`) and `dlt.quarantined.active` (`DltMetricsConfig` gauge in `ai-fraud-service`), complementing existing `dlt.messages` and Redis cache hit/miss counters (`idempotency.cache.*`, `account.balance.cache.*`).
2. **Distributed Tracing via Micrometer Tracing + OpenTelemetry + Tempo**:
   - Configured `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp` shipping OTLP traces to **Grafana Tempo** when `docker-compose.observability.yml` sets `MANAGEMENT_OTLP_TRACING_ENDPOINT=http://tempo:4318/v1/traces`.
   - Configured W3C `traceparent` + `baggage` propagation with `X-Correlation-Id` as a remote and correlated baggage field.
   - Registered `ObservationAwareBaggageThreadLocalAccessor` in `api-gateway` (`ObservabilityConfig`) paired with `ReactorBaggage.append(...)` in `CorrelationIdGlobalFilter` so `X-Correlation-Id` survives Netty event-loop thread hops.
   - Extended `outbox_events` (`V5__add_outbox_trace_context.sql`) with nullable `correlation_id` and `trace_parent` columns so `OutboxPublisherService` restores the parent trace context when dispatching to Kafka asynchronously, preserving a single continuous distributed trace into `ai-fraud-service` and `notification-service`.
3. **Structured JSON Logging**:
   - Configured `logback-spring.xml` with `LogstashEncoder` across all five services emitting `timestamp`, `level`, `service`, `logger`, `thread`, `traceId`, `spanId`, `X-Correlation-Id`, `message`, and `stack_trace`.
4. **Provisioned Grafana Stack (`docker-compose.observability.yml`)**:
   - Added Prometheus (`v2.51.0`), Grafana Tempo (`2.4.1`), and Grafana (`10.4.0`) with auto-provisioned datasources and dashboard JSON (`infra/observability/grafana/dashboards/nexusfin-observability.json`) covering request rate, p95/p99 latency, 4xx/5xx error rate, outbox backlog depth, DLT counts, Redis cache hit ratio, and JVM heap usage.

## Trade-offs & Consequences
* **Positive**: Complete visibility into request latency percentiles, outbox saturation, cache efficiency, DLT quarantine incidents, and cross-service async trace continuity.
* **Negative / Mitigation**: Storing `trace_parent` and `correlation_id` on `outbox_events` adds a lightweight schema migration (`V5`), and running the full observability stack adds ~768 MB memory overhead; this is mitigated by separating Prometheus, Tempo, and Grafana into `docker-compose.observability.yml` with explicit `256m` memory caps.
