# ADR-006: Redis as In-Memory Accelerator and PostgreSQL as ACID Source of Truth

## Status
**Accepted**

## Context & Problem Statement
In high-throughput financial transaction processing, identical requests are frequently re-transmitted by clients due to network timeouts, retries, or mobile app reconnections. Simultaneously, account balance inquiry endpoints (`/api/v1/accounts/number/{accountNumber}`) experience disproportionately high read volume compared to writes.

Relying exclusively on relational database queries for duplicate detection and balance inquiries creates notable performance bottlenecks:
1. Re-running database queries and unique constraint evaluations on identical requests consumes database connection pool slots and CPU.
2. Concurrent duplicate submissions with identical idempotency keys race to acquire row locks before hitting unique constraint violations in PostgreSQL.
3. High read traffic for popular merchant or reserve accounts places unnecessary read load on relational tables.

Conversely, using an in-memory cache as a primary store is strictly unacceptable for financial ledgers due to risks of data loss, dirty reads, or cache invalidation anomalies.

## Decision Drivers
* Sub-millisecond response times for retried idempotent requests.
* Non-blocking fast path for concurrent duplicate requests via distributed in-flight locking (`SET NX`).
* Strict consistency: PostgreSQL remains the immutable, authoritative source of truth under GAAP double-entry rules.
* Prevention of stale reads: Cache eviction for modified accounts must occur strictly **after** database commit (`AFTER_COMMIT`).
* Fail-Open Resilience: If Redis crashes, experiences network partitions, or times out, ledger operations must continue uninterrupted against PostgreSQL.
* Comprehensive observability via Micrometer cache hit/miss counters.

## Considered Options
1. **Redis as Primary Journal (Write-Behind / Cache-Aside with Asynchronous DB Sync)**: Unacceptable risk of financial data loss during unexpected Redis crashes or failovers prior to database persistence.
2. **Database-Only Idempotency & Queries**: Completely ACID compliant, but causes elevated database load during client retry storms and high-concurrency wallet reads.
3. **Redis as In-Memory Accelerator with PostgreSQL as Source of Truth**:
   - Check Redis `idem:{key}`: On cache hit, return stored `TransactionResponse` immediately.
   - On cache miss, acquire distributed in-flight lock (`idem:lock:{key}`) with short TTL (60s) using atomic `SET NX`. If lock cannot be acquired, reject concurrent request with `409 CONFLICT` (`ConcurrentTransactionException`).
   - Execute ACID transaction in PostgreSQL (pessimistic lock ordering, balance calculations, outbox event generation).
   - In Spring `TransactionSynchronizationManager`, register an `afterCommit()` hook:
     - Evict modified account balances (`acc:balance:{accountNumber}`) from Redis.
     - Persist `TransactionResponse` to `idem:{key}` with configurable TTL (default 24h).
     - Release in-flight lock.
   - On transaction rollback or failure, register an `afterCompletion()` hook to release the in-flight lock so clients can retry.
   - Wrap all Redis operations in defensive try/catch blocks so any Redis outage fails open directly to the database.

## Decision Outcome
Chosen option: **Option 3: Redis as In-Memory Accelerator with PostgreSQL as ACID Source of Truth**.

### Implementation Details
* **Service**: `com.nexusfin.ledger.service.RedisCacheService`
* **Configuration Properties**:
  * `nexusfin.redis.enabled`: Feature toggle (defaults to `true`).
  * `nexusfin.redis.idempotency-ttl-seconds`: 86,400s (24 hours).
  * `nexusfin.redis.balance-ttl-seconds`: 300s (5 minutes).
  * `nexusfin.redis.in-flight-lock-ttl-seconds`: 60s.
  * `spring.data.redis.timeout`: 500ms command timeout.
* **Key Patterns**:
  * `idem:{idempotencyKey}`: Cached `TransactionResponse` JSON payload.
  * `idem:lock:{idempotencyKey}`: In-flight processing lock (`SET NX`).
  * `acc:balance:{accountNumber}`: Cached `AccountResponse` JSON payload.
* **Post-Commit Eviction Guarantee**:
  ```java
  TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override
      public void afterCommit() {
          redisCacheService.evictAccounts(affectedAccounts);
          redisCacheService.storeIdempotentResponse(finalIdemKey, response);
      }

      @Override
      public void afterCompletion(int status) {
          if (status != STATUS_COMMITTED) {
              redisCacheService.releaseInFlightLock(finalIdemKey);
          }
      }
  });
  ```

### Observability Metrics
| Metric Name | Type | Description |
| :--- | :--- | :--- |
| `idempotency.cache.hit` | Counter | Count of duplicate requests served from Redis cache |
| `idempotency.cache.miss` | Counter | Count of idempotency requests requiring PostgreSQL evaluation |
| `account.balance.cache.hit` | Counter | Count of account balance inquiries served from Redis cache |
| `account.balance.cache.miss` | Counter | Count of account balance inquiries requiring PostgreSQL lookup |

## Consequences
* **Positive**: Significant reduction in database query volume under high concurrency; prevention of concurrent lock contention for duplicate client submissions; zero stale reads guaranteed by post-commit eviction.
* **Resilience**: Redis outages cause zero transaction processing failures; systems gracefully degrade to direct PostgreSQL querying with logged warnings.
