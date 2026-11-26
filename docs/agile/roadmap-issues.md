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
- [x] **#1.1**: Fix transactional outbox race condition with `SELECT ... FOR UPDATE SKIP LOCKED` and synchronous dispatch within `@Transactional`.
- [x] **#1.2**: Fix lock ordering: replace per-account loop with single ordered batch lock query `findAllByIdsWithPessimisticLock`.
- [x] **#1.3**: Add Testcontainers concurrency ITs (opposing transfers, random pairs, race idempotency, race insufficient funds).
- [x] **#1.4**: Remove hardcoded API key and database secrets from Java/YAML source.
- [x] **#1.5**: Make fraud service honest: implement strict JSON LLM parsing with heuristic fallback or align docs.
- [x] **#1.6**: Switch `ai-fraud-service` schema management to Flyway migrations (`V1__fraud_audit_log.sql`).
- [x] **#1.7**: Ensure Kafka consumers are idempotent via `processed_events` table or unique key on audit logs.
- [x] **#1.8**: Comprehensive README & documentation truth pass.

---

## Phase 2: Real Authentication and Authorization
- [x] **#2.1**: Provision Keycloak in Docker Compose with pre-configured realm import (`nexusfin-realm.json`).
- [x] **#2.2**: Configure API Gateway as reactive OAuth2 Resource Server (`SecurityWebFilterChain`).
- [x] **#2.3**: Enforce defense-in-depth resource server security and account ownership (`owner_id`) in `ledger-service`.
- [x] **#2.4**: Add automated security tests (401 unauthenticated, 403 unauthorized, 200 role-authorized).
- [x] **#2.5**: Update CI workflow e2e job with token acquisition, negative/positive checks, and add ADR-005.

---

## Phase 3: Redis Integration
- [x] **#3.1**: Implement Redis idempotency caching and account-balance read-through caching with post-commit eviction.
- [x] **#3.2**: Add Testcontainers Redis integration tests with fail-open fallback verification.
- [x] **#3.3**: Instrument Micrometer cache hit/miss metrics and add ADR-006.

---

## Phase 4: Real-Time WebSocket/STOMP Feed
- [x] **#4.1**: Build `notification-service` module consuming Kafka events with Redis Pub/Sub fan-out and STOMP endpoints.
- [x] **#4.2**: Add WebSocket/STOMP integration tests with authenticated sessions, channel authorization, and ADR-007.

---

## Phase 5: Resilience & Failure Handling
- [x] **#5.1**: Implement Kafka consumer retry with Dead-Letter Topic (`.DLT`) for poison messages.
- [x] **#5.2**: Provide admin visibility and Micrometer metrics for DLT backlog.
- [x] **#5.3**: Configure Resilience4j GET-only retries and circuit breakers/timeouts (bulkhead YAML defaults defined; route wiring deferred).
- [x] **#5.4**: Add fault-injection tests verifying poison message quarantine.

---

## Phase 6: Observability
- [x] **#6.1**: Instrument Prometheus endpoints on all services via Micrometer.
- [x] **#6.2**: Add Prometheus & Grafana stack (`docker-compose.observability.yml`) with pre-provisioned dashboards.
- [x] **#6.3**: Configure distributed tracing via Micrometer Tracing and OpenTelemetry.
- [x] **#6.4**: Standardize structured JSON logging with MDC correlation IDs.

---

## Phase 7: Load Testing and Benchmarks
- [x] **#7.1**: Write k6 load test scripts covering transfers, hot accounts, and duplicate idempotency keys.
- [x] **#7.2**: Execute benchmarks and commit raw results, bottleneck analysis, and hardware specifications to `docs/benchmarks/BENCHMARKS.md`.

---

## Phase 8: Angular Dashboard
- [x] **#8.1**: Create standalone Angular frontend with Keycloak PKCE authentication.
- [x] **#8.2**: Build account management, transfer forms, live transaction feed, and fraud audit dashboard.
- [x] **#8.3**: Add frontend unit tests and CI integration.

---

## Phase 9: Deployment, Polish & Demo
- [x] **#9.1**: Configure Vercel frontend (`vercel.json` + runtime env injection) & Oracle Cloud Always Free ARM64 backend overlay (`docker-compose.oci.yml`, Caddy TLS).
- [x] **#9.2**: Add Terraform infrastructure-as-code definitions (`infra/terraform/` for OCI `VM.Standard.A1.Flex`, VCN, Security List) & deployment guide (`docs/DEPLOYMENT_OCI_VERCEL.md`).
- [x] **#9.3**: Add multi-arch GHCR CD pipeline (`.github/workflows/cd.yml`), `LICENSE`, `CONTRIBUTING.md`, and final `README.md` overhaul. *(Live OCI/Vercel account apply & demo video pending owner provisioning.)*
