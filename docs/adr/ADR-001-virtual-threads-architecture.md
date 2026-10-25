# ADR-001: Adoption of Java 21 Virtual Threads (Project Loom)

## Status
**Accepted**

## Context & Problem Statement
NexusFin AI handles high-concurrency financial I/O workloads (JDBC database queries, inter-service HTTP calls, and AI LLM risk evaluations). 
Historically, high concurrency in Java was achieved either via:
1. **Platform Threads (Thread-per-request)**: Consumes ~1MB stack per thread; OS context switching caps scalability at ~2,000–5,000 concurrent threads.
2. **Reactive Programming (Spring WebFlux / Project Reactor)**: Non-blocking, but introduces steep cognitive complexity, fragmented stack traces, and poor interoperability with traditional blocking JDBC drivers.

## Decision Drivers
* Financial applications require transparent, linear stack traces for strict auditability and debugging.
* Need to support tens of thousands of concurrent transactions without thread exhaustion.
* Seamless integration with standard Spring Data JPA / JDBC drivers without complex reactive wrappers.

## Considered Options
1. Spring Boot WebFlux with R2DBC (Reactive)
2. Spring Boot MVC with traditional OS Platform Thread Pool
3. **Spring Boot MVC with Java 21 Virtual Threads (Project Loom)**

## Decision Outcome
Chosen option: **Option 3: Java 21 Virtual Threads** (`spring.threads.virtual.enabled=true`).

### Positive Consequences
* **Throughput**: Virtual threads are lightweight (~few hundred bytes) and scheduled by the JVM, enabling 50,000+ concurrent requests.
* **Code Simplicity**: Code is written in standard synchronous imperative style with clear stack traces.
* **Backward Compatibility**: Fully compatible with Flyway, Spring Data JPA, and existing transaction management annotations (`@Transactional`).

### Mitigations
* Avoid `synchronized` blocks around blocking I/O calls to prevent carrier thread pinning (favor `ReentrantLock` where needed).
