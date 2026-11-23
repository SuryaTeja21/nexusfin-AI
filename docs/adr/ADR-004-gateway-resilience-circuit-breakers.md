# ADR-004: End-to-End Microservice Resilience, Gateway Protection & Kafka Dead-Letter Topic (DLT)

## Status
**Accepted** (Expanded in Phase 5)

## Context & Problem Statement
In a distributed financial platform handling real-time double-entry ledgers, AI evaluations, and live WebSocket broadcasts:
1. Downstream service latency or crashes can cause cascading connection pool starvation across upstream callers.
2. Blindly retrying money-moving operations (`POST /api/v1/ledger/transactions`) across the network can cause catastrophic duplicate debits/credits.
3. Poison pill messages (malformed JSON or invalid schema payloads) sent to Kafka topics can cause infinite consumer retry loops, partition stall, and complete head-of-line blocking.
4. Transient database lock contention or momentary network hiccups must be retried with exponential backoff rather than dropped immediately.

## Decision Drivers
* Prevent cascading failure across microservice boundaries.
* Strictly enforce idempotency: **never blind-retry non-idempotent operations**.
* Guarantee that poison pills are quarantined immediately to a Dead-Letter Topic without blocking subsequent valid events.
* Provide administrative visibility and manual replay capabilities for quarantined events.
* Isolate thread pools and request concurrency via bulkheads and circuit breakers.

## Decision Outcome
We implemented a multi-layered resilience architecture across API Gateway and Kafka consumers:

### 1. API Gateway Edge Resilience
* **Resilience4j Reactive Circuit Breakers**: Attached to `/api/v1/accounts/**`, `/api/v1/ledger/**`, and `/api/v1/fraud/**` routes with a 50% failure threshold and 10-second open-state timeout. Degraded traffic forwards to `/fallback/ledger` or `/fallback/fraud` returning RFC 7807 Problem Details.
* **Idempotent-Only Gateway Retries**: Configured Spring Cloud Gateway `Retry` filter strictly for HTTP `GET` methods (3 retries, exponential backoff: 50ms initial, 500ms max, multiplier 2.0). **`POST`, `PUT`, and `DELETE` methods are explicitly excluded from automatic gateway retries**.
* **Resilience4j Bulkhead & TimeLimiter**: Configured semaphore-based concurrency limits (50 concurrent calls on ledger, 30 on fraud) and 5-second execution timeouts.

### 2. Kafka Consumer Resilience & Dead-Letter Topic (DLT) Quarantine
* **`DefaultErrorHandler` with `DeadLetterPublishingRecoverer`**: Configured in both `ai-fraud-service` and `notification-service`.
* **Exception Classification**:
  * **Non-Retryable Exceptions**: `JsonParseException`, `JsonMappingException`, `DeserializationException`, `ValidationException`, `IllegalArgumentException`. These bypass retry loops and are published **immediately** to `nexusfin.ledger.transactions.DLT`.
  * **Retryable Exceptions**: Transient database locks or network timeouts undergo exponential backoff (2 retries, 3 total attempts, 100ms initial interval, multiplier 2.0). If retries exhaust, they are routed to DLT.
* **DLT Metadata Preservation**: Spring Kafka automatically attaches headers (`kafka_dlt-original-topic`, `kafka_dlt-exception-fqcn`, `kafka_dlt-exception-message`, `kafka_dlt-original-offset`) to quarantined records.
* **DLT Administration & Replay**:
  * `DltEventListener` indexes incoming DLT events into an in-memory thread-safe `DltMessageStore`.
  * `GET /api/v1/fraud/dlt/messages` provides visibility into the quarantined backlog and counts.
  * `POST /api/v1/fraud/dlt/replay/{id}` allows operators to replay a quarantined message back to the primary topic after fixing upstream schemas or data bugs.
  * Micrometer counter `dlt.messages` instruments real-time alerting.

## Failure-Handling Matrix

| Failure Mode | Component | Handling Strategy | Recovery Mechanism |
| :--- | :--- | :--- | :--- |
| **Downstream Outage** | API Gateway | Resilience4j Circuit Breaker (50% trip, 10s wait) | RFC 7807 503 Problem Details |
| **Transient Read Blip** | API Gateway | Spring Cloud Gateway Retry (`GET` only, 3 retries, backoff 50ms-500ms) | Transparent retry |
| **Money-Moving POST Failure** | API Gateway / Ledger | No blind retry; client must re-submit with `Idempotency-Key` | Idempotency cache hit returns existing transaction |
| **Poison Pill Message** | Kafka Consumers | `DefaultErrorHandler` non-retryable classification | Quarantined to `nexusfin.ledger.transactions.DLT`; `dlt.messages` metric |
| **Transient Consumer DB Error** | Kafka Consumers | Exponential BackOff (3 attempts total, factor 2.0) | Retried with backoff; sent to DLT if unrecovered |
| **In-Flight Duplicate POST** | Ledger Service | Redis `SET NX` distributed in-flight lock | HTTP 409 Conflict; protects DB pool |
| **Redis Outage** | Ledger / Notification | Fail-open fallback | Graceful degradation to PostgreSQL / local routing |

## Positive Consequences
* Poison pills cannot crash or stall Kafka consumer group partitions.
* Subsequent legitimate transactions continue processing without head-of-line blocking.
* Administrators have full visibility into quarantined payloads and one-click replay.
* High edge traffic cannot cause cascading failures into internal microservices.
