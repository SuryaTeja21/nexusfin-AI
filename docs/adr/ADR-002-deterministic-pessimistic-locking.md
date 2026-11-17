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
Chosen option: **Option 3: Deterministic Ascending Lock Ordering in `LedgerService` via Batch Lock Acquisition**.

All account IDs involved in the transaction legs are resolved first, sorted in ascending UUID order, and locked in a single batch query enforcing strict lock hierarchy:

```java
Set<String> distinctAccountNumbers = legs.stream()
        .map(l -> l.getAccountNumber().trim())
        .collect(Collectors.toSet());

List<Account> resolvedAccounts = accountRepository.findByAccountNumberIn(distinctAccountNumbers);
if (resolvedAccounts.size() != distinctAccountNumbers.size()) {
    // throw AccountNotFoundException for missing accounts
}

List<UUID> sortedAccountIds = resolvedAccounts.stream()
        .map(Account::getId)
        .sorted()
        .collect(Collectors.toList());

List<Account> lockedAccounts = accountRepository.findAllByIdsWithPessimisticLock(sortedAccountIds);
if (lockedAccounts.size() != sortedAccountIds.size()) {
    throw new AccountNotFoundException("One or more accounts could not be locked");
}
```

Repository query with deterministic ascending order:
```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT a FROM Account a WHERE a.id IN :ids ORDER BY a.id ASC")
List<Account> findAllByIdsWithPessimisticLock(@Param("ids") Collection<UUID> ids);
```

### Positive Consequences
* **Mathematical Deadlock Immunity**: By enforcing a global lock hierarchy (lowest account ID to highest), circular waits are eliminated.
* **Deterministic Latency**: Threads queue up in FIFO database order rather than failing and re-executing.
