# ADR-003: Transactional Outbox Pattern for Distributed Financial Event Streaming

## Status
**Accepted**

## Context & Problem Statement
When a financial transaction is committed in the Core Ledger Service (`ledger-service`), downstream consumers such as the AI Fraud Detection Intelligence Service (`ai-fraud-service`) and audit subsystems must be notified in real time.
Dual-writing directly to both the database and Apache Kafka within the same business method introduces the classic distributed dual-write inconsistency:
1. If the database transaction commits but the Kafka publish fails, events are lost.
2. If Kafka publish succeeds but the database transaction rolls back (e.g. constraint violation), downstream consumers act on phantom transactions.

## Decision Drivers
* Financial accounting requires 100% guarantee that events reflect committed database state (no phantom events).
* Zero message loss (at-least-once delivery guarantee).
* Strict in-order event sequencing per account / transaction reference.
* Resilience against Kafka broker downtimes without degrading core ledger write availability.

## Considered Options
1. **Direct Synchronous Kafka Send in `@Transactional`**: High risk of dual-write inconsistency and coupling ledger latency to Kafka availability.
2. **Two-Phase Commit (XA / Distributed Transactions)**: Heavy performance overhead, not supported natively across modern cloud-native message brokers.
3. **Transactional Outbox Pattern with Polling Dispatcher**: Persist events as part of the ACID database transaction into an `outbox_events` table and asynchronously dispatch to Kafka with retries.

## Decision Outcome
Chosen option: **Option 3: Transactional Outbox Pattern**.

### Architecture
1. When `LedgerService.postTransaction()` executes, a `TransactionPostedEvent` is inserted into `outbox_events` (`status = PENDING`) within the identical database transaction.
2. An asynchronous `OutboxPublisherService` queries pending events, publishes them to Kafka topic `nexusfin.ledger.transactions` with key = `aggregateId`, and updates the status to `PUBLISHED`.
3. `ai-fraud-service` listens to the topic via `@KafkaListener` to run real-time fraud assessment and record audit trails.

### Positive Consequences
* **Atomicity**: The outbox event is committed if and only if the double-entry posting commits.
* **Resilience**: If Kafka is temporarily unavailable, ledger transactions continue to execute without failure; outbox events queue up and drain automatically when Kafka recovers.
* **Idempotency**: Downstream consumers can deduplicate events using `transactionReference`.
