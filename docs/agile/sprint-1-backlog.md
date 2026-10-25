# NexusFin AI — Sprint 1 Agile Backlog & Governance

**Sprint Goal:** Deliver the foundational microservice architecture and core ACID double-entry ledger engine capable of executing deterministic, deadlock-free financial postings with real-time AI fraud evaluation.

---

## 1. Governance: Definition of Ready (DoR) & Definition of Done (DoD)

### Definition of Ready (DoR)
A User Story is marked **Ready** for sprint execution only if:
1. Clear business value stated in standard user story format (`As a... I want... So that...`).
2. Testable Acceptance Criteria defined using Gherkin syntax (`Given... When... Then...`).
3. Financial invariants and account types (Asset, Liability, Equity, Revenue, Expense) explicitly declared.
4. Concurrency and locking semantics vetted to prevent database deadlocks.
5. Story pointed and estimated by the engineering team.

### Definition of Done (DoD)
A User Story is marked **Done** only if:
1. Code strictly passes all unit and integration tests.
2. GAAP/IFRS double-entry invariants holds ($\sum \text{Debits} = \sum \text{Credits}$).
3. Database migrations (Flyway) are reproducible and backward-compatible.
4. Zero unhandled exceptions (RFC 7807 Problem Details returned on errors).
5. JaCoCo line coverage $\ge 80\%$.
6. Clean build passing on GitHub Actions CI.
7. PR approved via peer code review.

---

## 2. Sprint 1 Epics & User Stories

### Epic 1: Multi-Module Cloud-Native Infrastructure
* **Story 1.1**: *As a Platform Engineer, I want a centralized Maven multi-module structure on Java 21 with Project Loom Virtual Threads, so that I/O-bound requests scale efficiently without thread exhaustion.*
  * **Status**: `DONE`

* **Story 1.2**: *As an API Gateway Consumer, I want distributed tracing headers (`X-Correlation-Id`) injected and propagated across all services, so that transactions can be traced across the microservice topology.*
  * **Status**: `DONE`

---

### Epic 2: Core ACID Double-Entry Financial Engine
* **Story 2.1**: *As an Account Holder, I want to create accounts with distinct accounting categories (Asset, Liability, Equity, Revenue, Expense) and ISO currencies, so that standard chart of accounts can be structured.*
  * **Status**: `DONE`
  * **Acceptance Criteria (Gherkin)**:
    ```gherkin
    Scenario: Account Creation Validation
      Given a unique account number "ACC-ASSET-001" and currency "USD"
      When a request is made with initial balance 1000.00
      Then the account is created with status ACTIVE
      And a normal debit balance is initialized
    ```

* **Story 2.2**: *As a FinTech System, I want multi-leg transactions to enforce debit-credit equality, so that ledger balances are mathematically guaranteed to balance.*
  * **Status**: `DONE`
  * **Acceptance Criteria (Gherkin)**:
    ```gherkin
    Scenario: Reject Unbalanced Multi-Leg Transaction
      Given a transaction with debit leg of 100.00 USD
      And credit leg of 90.00 USD
      When the transaction is posted
      Then the transaction is rejected with DoubleEntryImbalanceException
      And no account balance is modified
    ```

* **Story 2.3**: *As a High-Volume Ledger, I want concurrent transfers between common accounts to acquire locks in sorted order, so that PostgreSQL deadlocks are mathematically impossible.*
  * **Status**: `DONE`
  * **Acceptance Criteria (Gherkin)**:
    ```gherkin
    Scenario: Bidirectional Concurrent Transfer Deadlock Prevention
      Given two accounts ACC-A and ACC-B
      When Thread 1 executes transfer ACC-A -> ACC-B
      And Thread 2 executes transfer ACC-B -> ACC-A simultaneously
      Then both transactions acquire row locks in deterministic ascending sequence (ACC-A, then ACC-B)
      And both transactions complete successfully without deadlock
    ```

* **Story 2.4**: *As a Financial API Consumer, I want idempotent request deduplication, so that network retries do not result in duplicate account charges.*
  * **Status**: `DONE`

---

### Epic 3: AI Fraud Risk Assessment Pipeline
* **Story 3.1**: *As a Compliance Officer, I want real-time risk assessment via LLM with heuristic rule fallback, so that fraudulent transactions are flagged without bringing down transaction throughput if the LLM is unavailable.*
  * **Status**: `DONE`
