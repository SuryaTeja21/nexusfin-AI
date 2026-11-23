# NexusFin AI 🏦⚡
> **Enterprise-Grade Financial Transaction, ACID Ledger, and AI Intelligence Platform**  
> *Production-Ready Double-Entry Accounting, Transactional Outbox, Kafka Streaming, and Dual AI/Rule Fraud Engine*

---

## 1. Executive Summary & Architecture Overview

**NexusFin AI** is a cloud-native, high-throughput, microservice-oriented financial platform engineered in **Java 21 (LTS)** and **Spring Boot 3.2.x**, leveraging **JDK 21 Virtual Threads (Project Loom)** across all processing layers. 

The architecture strictly follows GAAP/IFRS double-entry accounting invariants, providing guaranteed ACID transactional consistency, deterministic batch lock ordering for deadlock prevention, transactional outbox event publishing with `SKIP LOCKED`, idempotent Kafka event consumption, and real-time dual LLM/heuristic fraud risk assessment.

```text
                                  [ Client / API Consumer ]
                                              │
                                              ▼
                             ┌─────────────────────────────────┐
                             │    api-gateway (Port 8080)      │
                             │  - API Key Security Filter      │
                             │  - Distributed Tracing (X-Corr) │
                             │  - Resilience4j Circuit Breaker │
                             │  - FallbackController           │
                             └────────────────┬────────────────┘
                                              │ (HTTP / Virtual Threads)
                     ┌────────────────────────┴────────────────────────┐
                     ▼                                                 ▼
       ┌───────────────────────────┐                     ┌───────────────────────────┐
       │  ledger-service (Port 8081)│                     │ai-fraud-service (Port 8082)│
       │  - Strict Double-Entry    │                     │ - Real-Time REST & Kafka  │
       │  - Batch Pessimistic Locks│                     │ - Dual LLM + Heuristics   │
       │  - Transactional Outbox   │                     │ - Idempotent Deduplication│
       └─────────────┬─────────────┘                     └─────────────┬─────────────┘
                     │                                                 │
                     ▼ (ACID Commits)                                  ▼ (Audit & Deduplication)
       ┌───────────────────────────┐                     ┌───────────────────────────┐
       │ postgres-ledger (5432)    │                     │ postgres-vector (5433)    │
       │ - Accounts & Balances     │                     │ - ai_fraud_audit_logs     │
       │ - Transactions & Entries  │                     │ - processed_events        │
       │ - outbox_events           │                     │ - Flyway V1 & V2 Migrated │
       └─────────────┬─────────────┘                     └─────────────▲─────────────┘
                     │                                                 │
                     ▼ (SKIP LOCKED Poll)                              │
       ┌───────────────────────────┐                                   │
       │  OutboxPublisherService   │                                   │
       │  - Synchronous Kafka Send │                                   │
       └─────────────┬─────────────┘                                   │
                     │                                                 │
                     ▼ (Topic: nexusfin.ledger.transactions)           │
       ┌───────────────────────────────────────────────────────────────┴─────────────┐
       │                          Apache Kafka Cluster (9092)                        │
       └─────────────────────────────────────────────────────────────────────────────┘
                                             ▲
                                             │ (Heartbeats & Discovery)
                           ┌─────────────────┴─────────────────┐
                           │   discovery-server (Port 8761)    │
                           │    Spring Cloud Netflix Eureka    │
                           └───────────────────────────────────┘
```

---

## 2. What Is and Is Not Implemented

To provide complete transparency for engineering reviews and production readiness audits:

| Feature / Capability | Status | Implementation Details |
| :--- | :--- | :--- |
| **GAAP/IFRS Double-Entry Ledger** | **Implemented** | Balanced multi-leg debit/credit validation ($\sum \text{Debits} = \sum \text{Credits}$), non-negative balance checks, immutable entries. |
| **Deadlock-Free Lock Ordering** | **Implemented** | Deterministic ID sorting and single-query batch locking via `findAllByIdsWithPessimisticLock` (`SELECT ... FOR UPDATE`). |
| **Idempotency Protection** | **Implemented** | Database-backed unique constraints on `idempotency_key` in `ledger-service` returning existing records without mutating balances. |
| **Transactional Outbox Pattern** | **Implemented** | Outbox events written in the same DB transaction; polled via `SELECT ... FOR UPDATE SKIP LOCKED` and published synchronously. |
| **Event Streaming (Kafka)** | **Implemented** | Real-time `nexusfin.ledger.transactions` event publication and consumption with transactional commit safety. |
| **Idempotent Kafka Consumer** | **Implemented** | `ai-fraud-service` deduplication table (`processed_events`) preventing duplicate processing and duplicate audit logs. |
| **Dual AI/Rule Fraud Engine** | **Implemented** | OpenAI / Spring AI integration with strict JSON schema parsing; automatic fallback to deterministic heuristic rules on timeout or LLM error. |
| **Schema Migrations** | **Implemented** | Flyway managed schemas across all services (`ledger-service` V1-V3, `ai-fraud-service` V1-V2) with `ddl-auto: validate`. |
| **Integration Concurrency Tests** | **Implemented** | Full Testcontainers integration suite (PostgreSQL 16, Kafka 3.7) testing opposing transfers, random pairs, outbox concurrency, and consumer idempotency. |
| **API Gateway Routing & Resilience** | **Implemented** | Path routing, correlation ID injection, API key security filter, and Resilience4j circuit breaker with `/fallback/ledger` degradation. |
| **Keycloak OAuth2 / OIDC PKCE** | **Implemented** | Keycloak realm (`nexusfin`), reactive resource server on gateway, defense-in-depth resource server on `ledger-service`, tenant isolation via `owner_id`. |
| **Redis Caching & Distributed Locks** | **Implemented** | Sub-millisecond idempotency cache hits, `SET NX` in-flight lock preventing duplicate concurrent transfers, post-commit balance eviction. |
| **WebSocket / STOMP Live Feed** | **Implemented** | `notification-service` with authenticated STOMP endpoint (`/ws`), admin broadcast (`/topic/transactions`), customer queue (`/user/queue/transactions`), and Redis Pub/Sub cluster fan-out. |
| **Dead-Letter Topic (.DLT) Quarantine** | **Implemented** | `DefaultErrorHandler` with non-retryable exception classification routing poison messages immediately to `nexusfin.ledger.transactions.DLT`, admin visibility endpoint (`/api/v1/fraud/dlt/messages`), one-click replay, and `dlt.messages` Micrometer metrics. |
| **Distributed Tracing & Dashboards** | *Planned (Phase 6)* | Basic correlation IDs and Micrometer health metrics active. OpenTelemetry and Grafana stack scheduled for Phase 6. |
| **k6 Load Benchmarks** | *Planned (Phase 7)* | Concurrency verified via JUnit Testcontainers. Formal k6 automated load benchmarks scheduled for Phase 7. |
| **PGVector Semantic Search** | *Future Enhancement* | Audit logs are stored in PostgreSQL; semantic vector indexing for AML similarity clusters is not yet active. |

---

## 3. Multi-Module Project Structure

```text
nexusfin-ai/
├── pom.xml                                      # Multi-Module Parent POM (Spring Boot 3.2.5, Spring Cloud 2023, Spring AI 0.8.1)
├── docker-compose.yml                           # Full stack orchestration (PostgreSQL 16, Kafka, Microservices)
├── discovery-server/                            # Netflix Eureka Service Registry (Port 8761)
│   ├── src/main/java/com/nexusfin/discovery/
│   └── src/main/resources/application.yml
├── api-gateway/                                 # Spring Cloud Gateway WebFlux (Port 8080)
│   ├── src/main/java/com/nexusfin/gateway/
│   │   ├── controller/FallbackController.java   # Resilience4j Circuit Breaker fallback response
│   │   ├── filter/CorrelationIdGlobalFilter.java # Injects X-Correlation-Id across services
│   │   ├── filter/FinTechAuditLoggingFilter.java # Tracks API request latencies and status codes
│   │   └── filter/GatewaySecurityFilter.java    # Enforces X-API-Key edge authentication
│   └── src/main/resources/application.yml
├── ledger-service/                              # Core ACID Double-Entry Financial Engine (Port 8081)
│   ├── src/main/java/com/nexusfin/ledger/
│   │   ├── controller/                          # REST Controllers & RFC 7807 Exception Handlers
│   │   ├── domain/entity/                       # JPA Entities (Account, Transaction, LedgerEntry, OutboxEvent)
│   │   ├── domain/model/                        # AccountType, AccountStatus, EntryType, OutboxStatus
│   │   ├── dto/                                 # Strongly validated Request & Response models
│   │   ├── repository/                          # Spring Data JPA with batch pessimistic locking
│   │   └── service/                             # LedgerService & OutboxPublisherService
│   ├── src/main/resources/db/migration/         # Flyway Migrations (PostgreSQL 16)
│   │   ├── V1__init_ledger_schema.sql
│   │   ├── V2__seed_chart_of_accounts.sql
│   │   └── V3__transactional_outbox.sql
│   └── src/test/                                # Concurrency ITs & Testcontainers test suite
└── ai-fraud-service/                            # AI Fraud Intelligence Microservice (Port 8082)
    ├── src/main/java/com/nexusfin/fraud/
    │   ├── consumer/TransactionEventListener.java # Idempotent Kafka consumer
    │   ├── controller/FraudDetectionController.java # Evaluation and audit log endpoints
    │   ├── domain/entity/                       # AuditLogEntry, ProcessedEvent
    │   ├── dto/                                 # Evaluation Request, Response, Event DTOs
    │   ├── repository/                          # AuditLogRepository, ProcessedEventRepository
    │   └── service/AiFraudDetectionService.java # Dual Spring AI + Heuristic rule engine
    ├── src/main/resources/db/migration/         # Flyway Migrations (PostgreSQL 16)
    │   ├── V1__fraud_audit_log.sql
    │   └── V2__processed_events.sql
    └── src/test/                                # Testcontainers Flyway schema & Idempotency tests
```

---

## 4. Financial Engineering & Core Invariants

### 4.1 Fundamental Accounting Invariants
$$\text{Assets} = \text{Liabilities} + \text{Equity} + (\text{Revenue} - \text{Expenses})$$

Every financial transaction must contain at least two entries (legs) that strictly balance:
$$\sum \text{Debits} = \sum \text{Credits}$$

### 4.2 Normal Balances & Balance Updating Math

| Account Category | Normal Balance | Balance Formula on DEBIT | Balance Formula on CREDIT |
| :--- | :--- | :--- | :--- |
| **ASSET** | **DEBIT** | $\text{Balance} + \text{Amount}$ | $\text{Balance} - \text{Amount}$ |
| **EXPENSE** | **DEBIT** | $\text{Balance} + \text{Amount}$ | $\text{Balance} - \text{Amount}$ |
| **LIABILITY** | **CREDIT** | $\text{Balance} - \text{Amount}$ | $\text{Balance} + \text{Amount}$ |
| **EQUITY** | **CREDIT** | $\text{Balance} - \text{Amount}$ | $\text{Balance} + \text{Amount}$ |
| **REVENUE** | **CREDIT** | $\text{Balance} - \text{Amount}$ | $\text{Balance} + \text{Amount}$ |

### 4.3 Deadlock Prevention via Deterministic Lock Ordering
When two concurrent transactions debit and credit the same accounts in reverse order (e.g. $A \to B$ and $B \to A$), standard database transactions encounter deadlocks.  
**NexusFin AI Solves This:** `LedgerService` extracts all account IDs involved in a multi-leg transaction, sorts them in deterministic ascending order, and executes a single ordered batch lock query:
```sql
SELECT a FROM Account a WHERE a.id IN :ids ORDER BY a.id ASC
```
Using `@Lock(LockModeType.PESSIMISTIC_WRITE)`. This eliminates N+1 query round trips and mathematically eliminates circular lock wait conditions.

### 4.4 Transactional Outbox Pattern with `SKIP LOCKED`
Events are persisted to `outbox_events` within the primary database transaction. The background `OutboxPublisherService` polls pending events using:
```sql
SELECT o FROM OutboxEvent o WHERE o.status = :status ORDER BY o.createdAt ASC
```
Locked with `PESSIMISTIC_WRITE` and `SKIP LOCKED`. It synchronously dispatches each message to Kafka via `kafkaTemplate.send(...).get(5, TimeUnit.SECONDS)` within `@Transactional(REQUIRES_NEW)`. If publishing fails, the transaction rolls back and increments `retry_count` without blocking concurrent worker threads.

### 4.5 Idempotent Kafka Consumer
`TransactionEventListener` in `ai-fraud-service` implements the Idempotent Consumer pattern using the `processed_events` table. If Kafka redelivers an event due to a network rebalance or broker retry, the consumer detects the existing record and skips re-evaluating the transaction, preventing duplicate audit records.

### 4.6 Failure-Handling & Microservice Resilience Matrix
NexusFin AI incorporates zero-trust, defense-in-depth failure resilience to isolate poison pills, transient errors, and cascading failures:

| Failure Mode | Component Affected | Detection & Mitigation Strategy | Fallback / Recovery Mechanism |
| :--- | :--- | :--- | :--- |
| **Downstream Microservice Outage** | API Gateway | Resilience4j Reactive Circuit Breakers (50% failure rate threshold, 10s wait state) | Standardized RFC 7807 503 Problem Details (`/fallback/ledger`, `/fallback/fraud`) |
| **Transient Read Latency / Blip** | API Gateway -> Microservices | Spring Cloud Gateway `Retry` filter (idempotent `GET` requests only, 3 retries, exponential backoff 50ms-500ms) | Automatic transparent retry; circuit breaker trips if persistent |
| **Money-Moving POST Failure** | API Gateway / Ledger Service | **Zero Blind Retries**: Gateway strictly disables retries on non-idempotent HTTP methods (`POST`, `PUT`, `DELETE`) | Client retries safely with original `Idempotency-Key`; Ledger Service returns cached record |
| **Poison Pill Message (Corrupt JSON)** | Kafka Consumers (`ai-fraud`, `notification`) | Spring Kafka `DefaultErrorHandler` with non-retryable exception classification (`JsonParseException`, `DeserializationException`, `IllegalArgumentException`) | Instant quarantine to `nexusfin.ledger.transactions.DLT` without retries or head-of-line blocking; metric `dlt.messages` incremented |
| **Transient Consumer Database Lock** | Kafka Consumers | `DefaultErrorHandler` with `ExponentialBackOffWithMaxRetries` (2 retries, 100ms initial, factor 2.0) | Retries 3 times total; routes to `.DLT` if all retries fail |
| **Quarantined Message Inspection & Recovery** | AI Fraud Service | In-memory `DltMessageStore` indexing all DLT events with metadata | `GET /api/v1/fraud/dlt/messages` (admin inspect) and `POST /api/v1/fraud/dlt/replay/{id}` (re-injects to Kafka) |
| **Concurrent In-Flight Duplicate Transfer** | Ledger Service | Redis `SET NX` distributed lock on `idempotency_key` | Immediate HTTP 409 Conflict rejection; protects DB connection pool |
| **Redis Outage** | Ledger / Notification | Fail-open fallback: cache failure logs warning and degrades directly to PostgreSQL / local routing | Business transactions proceed uninterrupted directly against PostgreSQL |

---

## 5. API Reference & Verification

All API calls must include the edge security key header `X-API-Key` (configured via `NEXUSFIN_API_KEY`).

### 5.1 Create Account
```bash
curl -X POST http://localhost:8080/api/v1/accounts \
  -H "Content-Type: application/json" \
  -H "X-API-Key: nexusfin-local-dev-key" \
  -d '{
    "accountNumber": "ACC-CUSTOMER-ALICE-USD",
    "accountName": "Alice Checking Account",
    "accountType": "LIABILITY",
    "currency": "USD",
    "initialBalance": 5000.0000
  }'
```

### 5.2 Balanced Multi-Leg Double-Entry Transaction
```bash
curl -X POST http://localhost:8080/api/v1/ledger/transactions \
  -H "Content-Type: application/json" \
  -H "X-API-Key: nexusfin-local-dev-key" \
  -d '{
    "transactionReference": "TX-2026-0001",
    "idempotencyKey": "IDEMP-UUID-1001",
    "transactionType": "TRANSFER",
    "description": "Customer settlement to central reserve",
    "legs": [
      {
        "accountNumber": "ACC-LIAB-CUSTOMER-CLEARING-USD",
        "entryType": "DEBIT",
        "amount": 1000.0000,
        "currency": "USD"
      },
      {
        "accountNumber": "ACC-ASSET-CENTRAL-RESERVE-USD",
        "entryType": "CREDIT",
        "amount": 1000.0000,
        "currency": "USD"
      }
    ]
  }'
```

### 5.3 High-Level Account Transfer
```bash
curl -X POST http://localhost:8080/api/v1/ledger/transfers \
  -H "Content-Type: application/json" \
  -H "X-API-Key: nexusfin-local-dev-key" \
  -d '{
    "sourceAccountNumber": "ACC-ASSET-CENTRAL-RESERVE-USD",
    "destinationAccountNumber": "ACC-LIAB-CUSTOMER-CLEARING-USD",
    "amount": 250.0000,
    "currency": "USD",
    "idempotencyKey": "IDEMP-TRF-0099",
    "description": "Direct wallet fund transfer"
  }'
```

### 5.4 AI Fraud Risk Assessment
```bash
curl -X POST http://localhost:8080/api/v1/fraud/evaluate \
  -H "Content-Type: application/json" \
  -H "X-API-Key: nexusfin-local-dev-key" \
  -d '{
    "transactionReference": "TX-2026-0001",
    "amount": 1250000.00,
    "currency": "USD",
    "merchantCategory": "CRYPTO_EXCHANGE",
    "geolocation": "SUSPICIOUS_UNKNOWN",
    "clientIp": "198.51.100.42"
  }'
```

---

## 6. Local Orchestration & Running Tests

### 6.1 Run the Full Test Suite
All unit tests and Testcontainers integration tests (requiring Docker):
```bash
./mvnw clean verify
```

To run unit tests only without starting Docker containers:
```bash
./mvnw test -Dtest="*UnitTest,*Tests,AiFraudDetectionServiceTest" -Dsurefire.failIfNoSpecifiedTests=false
```

### 6.2 Start Local Infrastructure with Docker Compose
```bash
# 1. Copy local configuration
cp .env.example .env

# 2. Start all services in detached mode
docker compose up -d

# 3. View cluster status
docker compose ps

# 4. View real-time distributed logs
docker compose logs -f ledger-service ai-fraud-service api-gateway
```

---

## 7. Sprint Roadmap

- [x] **Phase 0: Housekeeping**: Clean repository hygiene, `.gitignore` updates, `DEVELOPMENT.md` guide, and agile roadmap backlog.
- [x] **Phase 1: Foundation & Reliability Core**:
  - [x] Transactional Outbox pattern with `SELECT ... FOR UPDATE SKIP LOCKED` and synchronous dispatch.
  - [x] Deterministic batch lock ordering via `findAllByIdsWithPessimisticLock` eliminating deadlocks.
  - [x] Testcontainers concurrency test suite (opposing transfers, random pairs, race idempotency, race funds).
  - [x] Secret removal from source and YAML configurations; clean `.env` injection.
  - [x] Dual AI fraud engine with strict JSON schema parsing and heuristic rule fallback.
  - [x] Flyway migrations across all services (`ledger-service` V1-V3, `ai-fraud-service` V1-V2).
  - [x] Idempotent Kafka consumer with `processed_events` deduplication store.
  - [x] Comprehensive documentation and architecture truth pass.
- [ ] **Phase 2: Authentication & Authorization**: Keycloak OAuth2 / OIDC PKCE resource server on API Gateway with tenant account ownership (`owner_id`) checks.
- [ ] **Phase 3: High-Performance Caching**: Redis idempotency caching and account-balance read-through caching with post-commit eviction.
- [ ] **Phase 4: Real-Time Notifications**: WebSocket/STOMP push service consuming Kafka events with Redis fan-out.
- [ ] **Phase 5: Advanced Resilience**: Kafka consumer retry with Dead-Letter Topic (`.DLT`) for poison messages and Micrometer metrics.
- [ ] **Phase 6: Full-Stack Observability**: Prometheus metrics, Grafana dashboards, and OpenTelemetry distributed tracing.
- [ ] **Phase 7: Load Testing**: Automated k6 performance benchmarks under high contention.
- [ ] **Phase 8: Frontend Dashboard**: Angular standalone client with Keycloak PKCE login and live transaction ledger feed.
- [ ] **Phase 9: Production Release**: AWS Terraform infrastructure, CI/CD deployment pipeline, and `v1.0.0` release.
