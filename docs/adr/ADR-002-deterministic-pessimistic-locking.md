# ADR-002: Deterministic Pessimistic Locking for Financial Invariant Deadlock Prevention

## Status
**Accepted**

## Context & Problem Statement
In a double-entry ledger, multi-leg transfers debit one account and credit another. Under high concurrent traffic, two bidirectional transactions may occur simultaneously:
* Transaction 1: Transfer from Account $A$ to Account $B$ (Locks $A$, requests lock on $B$).
* Transaction 2: Transfer from Account $B$ to Account $A$ (Locks $B$, requests lock on $A$).

In standard relational databases (PostgreSQL), this produces a **Circular Wait Deadlock** (`ERROR: deadlock detected - 40P01`), causing one or both transactions to abort.

## Decision Drivers
* Financial postings must maintain strict ACID consistency.
* Lock contention under heavy trading or peer-to-peer settlement must be resolved without transaction aborts or cascading rollbacks.
* Optimistic locking (`@Version`) causes high retry failure rates under hotspot contention (e.g., a popular merchant receiving 1,000 payments/second).

## Considered Options
1. **Optimistic Locking with Exponential Backoff Retry**: High CPU churn and high rollback rates under hotspot accounts.
2. **Database-level Lock Ordering Trigger**: Complex to maintain across migrations.
3. **Application-level Deterministic Pessimistic Write Lock Ordering (`SELECT ... FOR UPDATE`)**: Order all account identifiers in ascending order before acquiring locks.

## Decision Outcome
Chosen option: **Option 3: Deterministic Ascending Lock Ordering in `LedgerService`**.

```java
List<String> sortedAccountNumbers = legs.stream()
        .map(l -> l.getAccountNumber().trim())
        .distinct()
        .sorted()
        .collect(Collectors.toList());

for (String accNo : sortedAccountNumbers) {
    accountRepository.findByAccountNumberWithLock(accNo);
}
```

### Positive Consequences
* **Mathematical Deadlock Immunity**: By enforcing a global lock hierarchy (lowest account ID to highest), circular waits are eliminated.
* **Deterministic Latency**: Threads queue up in FIFO database order rather than failing and re-executing.
