# NexusFin AI 🏦⚡
> **Enterprise-Grade Financial Transaction, ACID Ledger, and AI Intelligence Platform**  
> *Sprint 1: Foundation & Core Double-Entry Ledger Implementation*

---

## 1. Executive Summary & Architecture Overview

**NexusFin AI** is a cloud-native, high-throughput, microservice-oriented financial platform engineered in **Java 21 (LTS)** and **Spring Boot 3.2.x**, leveraging **JDK 21 Virtual Threads (Project Loom)** across all processing layers. 

The architecture strictly follows GAAP/IFRS double-entry accounting invariants, providing guaranteed ACID transactional consistency, pessimistic lock deadlock prevention, idempotent deduplication, and real-time AI-powered fraud risk assessment.

```text
                                [ Client / API Consumer ]
                                            │
                                            ▼
                           ┌─────────────────────────────────┐
                           │    api-gateway (Port 8080)      │
                           │  - Distributed Tracing (X-Corr) │
                           │  - Inbound/Outbound Audit Log   │
                           └────────────────┬────────────────┘
                                            │
                   ┌────────────────────────┴────────────────────────┐
                   ▼                                                 ▼
     ┌───────────────────────────┐                     ┌───────────────────────────┐
     │  ledger-service (Port 8081)│                     │ai-fraud-service (Port 8082)│
     │  - Strict Double-Entry    │                     │ - Spring AI / OpenAI LLM  │
     │  - Deterministic Row Locks│                     │ - Heuristic Rule Engine   │
     │  - Virtual Threads Loom   │                     │ - Vector Store Audit Logs │
     └─────────────┬─────────────┘                     └─────────────┬─────────────┘
                   │                                                 │
                   ▼                                                 ▼
      ┌─────────────────────────┐                       ┌─────────────────────────┐
      │ postgres-ledger (5432)  │                       │ postgres-vector (5433)  │
      │  ACID Ledger & Flyway   │                       │  PGVector & Embeddings  │
      └─────────────────────────┘                       └─────────────────────────┘
                                           ▲
                                           │
                         ┌─────────────────┴─────────────────┐
                         │   discovery-server (Port 8761)    │
                         │    Spring Cloud Netflix Eureka    │
                         └───────────────────────────────────┘
```

---

## 2. Multi-Module Project Structure

```text
nexusfin-ai/
├── pom.xml                                      # Multi-Module Parent POM (Spring Boot 3.2.5, Spring Cloud 2023, Spring AI 0.8.1)
├── docker-compose.yml                           # Full stack orchestration (PostgreSQL 16, PGVector, Microservices)
├── discovery-server/                            # Netflix Eureka Service Registry (Port 8761)
│   ├── src/main/java/com/nexusfin/discovery/
│   └── src/main/resources/application.yml
├── api-gateway/                                 # Spring Cloud Gateway (Port 8080)
│   ├── src/main/java/com/nexusfin/gateway/
│   │   ├── filter/CorrelationIdGlobalFilter.java # Injects X-Correlation-Id
│   │   └── filter/FinTechAuditLoggingFilter.java # Tracks API request latencies
│   └── src/main/resources/application.yml
├── ledger-service/                              # Core ACID Double-Entry Financial Engine (Port 8081)
│   ├── src/main/java/com/nexusfin/ledger/
│   │   ├── controller/                          # REST Controllers & RFC 7807 Exception Handlers
│   │   ├── domain/                              # JPA Entities (Account, Transaction, LedgerEntry)
│   │   ├── domain/model/                        # AccountType, AccountStatus, EntryType, TransactionType
│   │   ├── dto/                                 # Strongly validated Request & Response models
│   │   ├── repository/                          # Spring Data JPA with @Lock(PESSIMISTIC_WRITE)
│   │   └── service/                             # LedgerService (ACID, Lock Ordering, Idempotency)
│   ├── src/main/resources/db/migration/         # Flyway DDL & Chart of Accounts seed scripts
│   │   ├── V1__init_ledger_schema.sql
│   │   └── V2__seed_chart_of_accounts.sql
│   └── src/test/                                # Double-entry invariant & concurrency test suite
└── ai-fraud-service/                            # AI Fraud Intelligence Microservice (Port 8082)
    ├── src/main/java/com/nexusfin/fraud/
    │   ├── controller/                          # Real-time risk evaluation & audit endpoints
    │   ├── domain/                              # AuditLogEntry JPA entity
    │   ├── dto/                                 # FraudEvaluationRequest & FraudEvaluationResponse
    │   └── service/                             # AiFraudDetectionService (Spring AI + Rule Engine)
    └── src/main/resources/application.yml
```

---

## 3. Financial Engineering & Accounting Principles

### 3.1 Fundamental Accounting Invariants
$$\text{Assets} = \text{Liabilities} + \text{Equity} + (\text{Revenue} - \text{Expenses})$$

Every financial transaction must contain at least two entries (legs) that strictly balance:
$$\sum \text{Debits} = \sum \text{Credits}$$

### 3.2 Normal Balances & Balance Updating Math

| Account Category | Normal Balance | Balance Formula on DEBIT | Balance Formula on CREDIT |
| :--- | :--- | :--- | :--- |
| **ASSET** | **DEBIT** | $\text{Balance} + \text{Amount}$ | $\text{Balance} - \text{Amount}$ |
| **EXPENSE** | **DEBIT** | $\text{Balance} + \text{Amount}$ | $\text{Balance} - \text{Amount}$ |
| **LIABILITY** | **CREDIT** | $\text{Balance} - \text{Amount}$ | $\text{Balance} + \text{Amount}$ |
| **EQUITY** | **CREDIT** | $\text{Balance} - \text{Amount}$ | $\text{Balance} + \text{Amount}$ |
| **REVENUE** | **CREDIT** | $\text{Balance} - \text{Amount}$ | $\text{Balance} + \text{Amount}$ |

### 3.3 Deadlock Prevention via Deterministic Lock Ordering
When two concurrent transactions attempt to debit and credit the same accounts in reverse order (e.g. $A \to B$ and $B \to A$), standard database transactions encounter deadlocks.  
**NexusFin AI Solves This:** `LedgerService` extracts all account IDs involved in a multi-leg transaction, **sorts them deterministically**, and acquires row-level pessimistic write locks (`SELECT ... FOR UPDATE`) in ascending sorted sequence. This mathematically eliminates circular lock wait conditions.

### 3.4 Zero-Loss Idempotency Key Pattern
Every posting request carries an `idempotencyKey`. The `TransactionRepository` uses a unique database constraint. If an identical key is presented, the service immediately returns the existing transaction response without altering balances.

---

## 4. API Reference & Verification

### 4.1 Create Account
```bash
curl -X POST http://localhost:8080/api/v1/accounts \
  -H "Content-Type: application/json" \
  -d '{
    "accountNumber": "ACC-CUSTOMER-ALICE-USD",
    "accountName": "Alice Checking Account",
    "accountType": "LIABILITY",
    "currency": "USD",
    "initialBalance": 5000.0000
  }'
```

### 4.2 Balanced Multi-Leg Double-Entry Transaction
```bash
curl -X POST http://localhost:8080/api/v1/ledger/transactions \
  -H "Content-Type: application/json" \
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

### 4.3 High-Level Account Transfer
```bash
curl -X POST http://localhost:8080/api/v1/ledger/transfers \
  -H "Content-Type: application/json" \
  -d '{
    "sourceAccountNumber": "ACC-ASSET-CENTRAL-RESERVE-USD",
    "destinationAccountNumber": "ACC-LIAB-CUSTOMER-CLEARING-USD",
    "amount": 250.0000,
    "currency": "USD",
    "idempotencyKey": "IDEMP-TRF-0099",
    "description": "Direct wallet fund transfer"
  }'
```

### 4.4 AI Fraud Risk Assessment
```bash
curl -X POST http://localhost:8080/api/v1/fraud/evaluate \
  -H "Content-Type: application/json" \
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

## 5. Local Orchestration with Docker Compose

To boot the entire infrastructure (PostgreSQL, PGVector, Discovery Server, API Gateway, Ledger Service, and AI Fraud Service):

```bash
# 1. Start all containers in detached mode
docker compose up -d

# 2. Check cluster service health
docker compose ps

# 3. View live distributed logs
docker compose logs -f ledger-service api-gateway
```

---

## 6. Sprint Roadmap (Agile Methodology)

- [x] **Sprint 1 (Done)**: Foundation multi-module Maven setup, Java 21 Virtual Threads, Eureka discovery server, Spring Cloud Gateway with Correlation IDs, Core ACID Ledger service with PostgreSQL & Flyway migrations, double-entry mathematical invariants, pessimistic lock deadlock prevention, idempotency deduplication, Spring AI fraud evaluation pipeline, and Docker Compose orchestration.
- [ ] **Sprint 2 (Planned)**: Event-driven event sourcing with Apache Kafka for transaction journaling, distributed Outbox pattern, and Dead Letter Queues (DLQ).
- [ ] **Sprint 3 (Planned)**: OpenTelemetry (OTel) tracing, Prometheus/Grafana dashboards, PGVector semantic vector indexing for AML pattern clustering, and automated reconciliation jobs.
