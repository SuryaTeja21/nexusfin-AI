# NexusFin: Project Roadmap & Issue Backlog

This backlog mirrors the implementation phases defined in `docs/IMPROVEMENT_PLAN.md`.

---

## Phase 0: Housekeeping
- [x] **#0.1**: Add `*.pdf`, `.env`, `target/`, `.idea/`, `.DS_Store` to `.gitignore`.
- [x] **#0.2**: Clean up uncommitted whitespace changes.
- [x] **#0.3**: Add `docs/DEVELOPMENT.md` setup and execution guide.
- [x] **#0.4**: Create roadmap issue list.

---

## Phase 1: Make Existing Claims True
- [ ] **#1.1**: Fix transactional outbox race condition with `SELECT ... FOR UPDATE SKIP LOCKED` and synchronous dispatch within `@Transactional`.
- [ ] **#1.2**: Fix lock ordering: replace per-account loop with single ordered batch lock query `findAllByIdsWithPessimisticLock`.
- [ ] **#1.3**: Add Testcontainers concurrency ITs (opposing transfers, random pairs, race idempotency, race insufficient funds).
- [ ] **#1.4**: Remove hardcoded API key and database secrets from Java/YAML source.
- [ ] **#1.5**: Make fraud service honest: implement strict JSON LLM parsing with heuristic fallback or align docs.
- [ ] **#1.6**: Switch `ai-fraud-service` schema management to Flyway migrations (`V1__fraud_audit_log.sql`).
- [ ] **#1.7**: Ensure Kafka consumers are idempotent via `processed_events` table or unique key on audit logs.
- [ ] **#1.8**: Comprehensive README & documentation truth pass.

---

## Phase 2: Real Authentication and Authorization
- [ ] **#2.1**: Provision Keycloak in Docker Compose with pre-configured realm import (`nexusfin-realm.json`).
- [ ] **#2.2**: Configure API Gateway as reactive OAuth2 Resource Server (`SecurityWebFilterChain`).
- [ ] **#2.3**: Enforce defense-in-depth resource server security and account ownership (`owner_id`) in `ledger-service`.
- [ ] **#2.4**: Add automated security tests (401 unauthenticated, 403 unauthorized, 200 role-authorized).
- [ ] **#2.5**: Update CI workflow e2e job with token acquisition and verification.

---

## Phase 3: Redis Integration
- [ ] **#3.1**: Implement Redis idempotency caching and account-balance read-through caching with post-commit eviction.
- [ ] **#3.2**: Add Testcontainers Redis integration tests with fail-open fallback verification.
- [ ] **#3.3**: Instrument Micrometer cache hit/miss metrics.

---

## Phase 4: Real-Time WebSocket/STOMP Feed
- [ ] **#4.1**: Build `notification-service` module consuming Kafka events with Redis Pub/Sub fan-out and STOMP endpoints.
- [ ] **#4.2**: Add WebSocket/STOMP integration tests with authenticated sessions and channel authorization.

---

## Phase 5: Resilience & Failure Handling
- [ ] **#5.1**: Implement Kafka consumer retry with Dead-Letter Topic (`.DLT`) for poison messages.
- [ ] **#5.2**: Provide admin visibility and Micrometer metrics for DLT backlog.
- [ ] **#5.3**: Configure Resilience4j retries and bulkheads for idempotent operations.
- [ ] **#5.4**: Add fault-injection tests verifying poison message quarantine.

---

## Phase 6: Observability
- [ ] **#6.1**: Instrument Prometheus endpoints on all services via Micrometer.
- [ ] **#6.2**: Add Prometheus & Grafana stack (`docker-compose.observability.yml`) with pre-provisioned dashboards.
- [ ] **#6.3**: Configure distributed tracing via Micrometer Tracing and OpenTelemetry.
- [ ] **#6.4**: Standardize structured JSON logging with MDC correlation IDs.

---

## Phase 7: Load Testing and Benchmarks
- [ ] **#7.1**: Write k6 load test scripts covering transfers, hot accounts, and duplicate idempotency keys.
- [ ] **#7.2**: Execute benchmarks and commit raw results, bottleneck analysis, and hardware specifications to `docs/benchmarks/BENCHMARKS.md`.

---

## Phase 8: Angular Dashboard
- [ ] **#8.1**: Create standalone Angular frontend with Keycloak PKCE authentication.
- [ ] **#8.2**: Build account management, transfer forms, live transaction feed, and fraud audit dashboard.
- [ ] **#8.3**: Add frontend unit tests and CI integration.

---

## Phase 9: Deployment, Polish & Demo
- [ ] **#9.1**: Configure AWS cloud deployment (EC2/RDS) and optional S3 export.
- [ ] **#9.2**: Add Terraform infrastructure-as-code definitions.
- [ ] **#9.3**: Finalize CI/CD release pipeline, architecture diagrams, and release `v1.0.0`.
