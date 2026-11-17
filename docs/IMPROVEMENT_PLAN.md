# NexusFin: Full Improvement Plan (for an AI coding agent)

> **How to use this file:** Put it at `docs/IMPROVEMENT_PLAN.md`. Tell the agent:
> *"Read docs/IMPROVEMENT_PLAN.md fully. Execute ONE phase at a time. After each phase, stop, show me
> `git diff --stat`, test results, and the acceptance checklist. Do not start the next phase until I say so."*

---

## 0. Goal and ground rules

**Goal:** Turn NexusFin into a portfolio project where every resume claim is backed by code, tests, CI and
measured numbers, so it can be defended in a technical interview.

**Stack today:** Java 21, Spring Boot 3.2.5, Spring Cloud 2023.0.3, 4 modules (`discovery-server`,
`api-gateway` (reactive WebFlux), `ledger-service`, `ai-fraud-service`), PostgreSQL 16 + Flyway V1-V3,
Kafka 3.7 (KRaft) with transactional outbox, Resilience4j circuit breakers on the gateway, Docker Compose,
GitHub Actions (unit tests + live e2e job).

### Rules the agent MUST follow
1. **Never invent results.** Never write a performance number, coverage figure or benchmark into any file
   unless it was produced by a script in this repo that was actually run. Use `TBD (run k6 to fill)` otherwise.
2. **Every feature ships with tests** in the same commit series. No test, not done.
3. **Small conventional commits** (`feat:`, `fix:`, `test:`, `docs:`, `chore:`, `ci:`), one logical change each.
4. **Do not break CI.** Run `./mvnw clean verify` before declaring a phase done. If no JDK is available
   locally, use the Codespaces devcontainer or tell me instead of skipping.
5. **No secrets in source.** Config comes from env vars; commit only `.env.example` with placeholders.
6. **Keep docs truthful.** If code and README/ADR disagree, fix the one that is wrong in the same PR.
7. **Do not touch** `Suryateja_Chintala*.pdf` or other personal files. Add them to `.gitignore`.
8. **Ask before** adding any dependency not named in this plan.
9. Prefer Testcontainers (real PostgreSQL/Kafka/Redis) over H2/mocks for integration tests.
10. Each phase ends with an updated README section and ADR when a significant decision is made.

---

## Phase 0: Housekeeping (0.5 day)

| # | Task | Acceptance |
|---|------|-----------|
| 0.1 | Add `*.pdf`, `.env`, `target/`, `.idea/`, `.DS_Store` to `.gitignore`. Confirm no resume PDFs are tracked (`git ls-files \| grep -i pdf` is empty). | Clean `git status` |
| 0.2 | Commit or revert the pending `.devcontainer/devcontainer.json` whitespace change. | No uncommitted changes |
| 0.3 | Add `docs/DEVELOPMENT.md`: JDK 21 install, Docker requirement, how to run unit tests, integration tests, and the full stack. | File exists, steps verified |
| 0.4 | Create a GitHub Project/issue list mirroring the phases below (optional but good for visibility). | Issues exist |

---

## Phase 1: Make existing claims true (Days 1-5)

### 1.1 Fix the transactional outbox (highest-value bug)
**Problem:** `OutboxPublisherService.dispatchPendingEvents()` uses `REQUIRES_NEW` but `kafkaTemplate.send()` is
async. The status update in `whenComplete` runs outside the transaction; the next 1s poll can re-read the same
`PENDING` rows and publish duplicates. The synchronous `catch` increments `retryCount` without checking the cap.

**Do:**
- Add a repository query using `SELECT ... FROM outbox_events WHERE status='PENDING' ORDER BY created_at
  LIMIT :batch FOR UPDATE SKIP LOCKED` (native query or `@Lock` + `jakarta.persistence.lock.timeout=-2`).
- In one `@Transactional` method: fetch the batch, send each event, **block on the result**
  (`send(...).get(timeout)`) so success/failure is known inside the transaction, then set `PUBLISHED` +
  `processedAt`, or increment `retryCount`.
- Apply the cap (`>= maxRetries` -> `FAILED`) in **both** the success-callback failure path and the exception path.
- Make `maxRetries`, `batchSize`, `pollIntervalMs` configurable properties.
- Use the aggregate id (account/transaction id) as the Kafka message key; set `acks=all` and
  `enable.idempotence=true` on the producer.
- Add a Flyway migration `V4` if a column/index is needed (e.g. index on `(status, created_at)` already exists; verify).

**Tests (Testcontainers PostgreSQL + Kafka):**
- Happy path: event is published exactly once and marked `PUBLISHED`.
- Two publisher instances running concurrently never publish the same event twice (SKIP LOCKED proof).
- Broker down: `retryCount` increments, reaches the cap, status becomes `FAILED`, loop does not spin forever.

**Acceptance:** Tests pass in CI. ADR-003 updated with "at-least-once delivery; consumers must be idempotent".

### 1.2 Fix lock ordering to match the docs (or the docs to match the code)
**Preferred fix (change the code):**
- Resolve all account ids first, then lock them with ONE query: `findAllByIdsWithPessimisticLock(ids)` using
  `ORDER BY id` and `PESSIMISTIC_WRITE`. Remove the per-account loop of `findByAccountNumberWithLock`.
- Verify the returned list size equals the number of distinct ids; otherwise throw `AccountNotFoundException`.
- Update ADR-002 and README to describe exactly what the code does.

**Acceptance:** Existing 5 ledger tests still pass; ADR-002 and code agree.

### 1.3 Concurrency and correctness tests (Testcontainers PostgreSQL, Flyway ENABLED)
Create a base class `AbstractPostgresIT` with a shared `PostgreSQLContainer` (`postgres:16-alpine`),
`@DynamicPropertySource`, `spring.flyway.enabled=true`, `ddl-auto=validate`.

Tests to add in `ledger-service/src/test/.../concurrency/`:
1. **Opposing transfers, no deadlock:** 100 concurrent transfers (50 A->B, 50 B->A) using an
   `ExecutorService` (virtual threads) + `CountDownLatch` start gate. Assert: all complete within a timeout,
   zero deadlock/lock-timeout exceptions, **sum of all balances unchanged**, A and B balances equal expected.
2. **Many accounts, random pairs:** 20 accounts, 500 random transfers, assert conserved total and no negative
   balances for asset accounts.
3. **Idempotency under race:** 50 threads submit the same `idempotencyKey` simultaneously; exactly one
   transaction row exists and all callers receive the same transaction id (handle the unique-constraint
   violation by re-reading the existing row; fix the service if this test fails).
4. **Insufficient funds under race:** balance 100, 20 threads each debit 10 -> exactly 10 succeed.

**Also:** Add real **Mockito** unit tests for `LedgerService` validation logic and `AiFraudDetectionService`
(mock `ChatClient`, repository). Delete the unused Mockito claim from docs if not done.

**Acceptance:** `./mvnw verify` green. JaCoCo report shows `ledger-service` service-layer coverage; record the real
number in the README only after reading it from the report.

### 1.4 Remove hardcoded secrets
- Delete defaults for `nexusfin.security.api-key` and DB passwords from Java/YAML (`${VAR}` with no default for
  secrets in non-test profiles). Keep placeholders in `.env.example`; `docker-compose.yml` reads from `.env`.
- Compose defaults for local dev are acceptable only if clearly marked "local only" and not reused elsewhere.
- Rotate nothing real (these are demo values), but ensure CI passes secrets via workflow `env`.

### 1.5 Make the fraud service honest (pick ONE)
- **Option A (recommended, small):** Use the LLM response. Ask the model for strict JSON
  (`riskScore`, `riskLevel`, `reason`), parse it with validation, and fall back to the heuristics on parse error,
  timeout, or missing API key. Persist which engine decided (`engine = LLM | HEURISTIC`). Add tests with a mocked
  `ChatClient` for valid JSON, malformed JSON, and exception.
- **Option B:** Remove "AI-driven" and "PGVector/embeddings" wording from README and describe it as
  "rule-based fraud scoring with an optional LLM advisory log".
- Either way, remove README claims about PGVector/embeddings unless Option C is built: store transaction
  embeddings in `postgres-vector` and add a similarity query ("find similar past flagged transactions").

### 1.6 Switch fraud-service schema management to Flyway
Replace `ddl-auto: update` with Flyway migrations (`V1__fraud_audit_log.sql`) and `ddl-auto: validate`.

### 1.7 Make the consumer idempotent
Store processed event ids (table `processed_events` with unique `event_id`) or use a natural unique key on the
audit log, so duplicate Kafka deliveries do not create duplicate audit entries. Test by sending the same event twice.

### 1.8 README and docs truth pass
- Update the roadmap (Kafka/outbox done), project structure (add `GatewaySecurityFilter`, `FallbackController`),
  architecture diagram (add Kafka, show async path), lock-ordering description.
- Add a **"What is and is not implemented"** table. Honest limits impress reviewers.

**Phase 1 exit checklist:** outbox bug fixed + tested; concurrency tests green on real PostgreSQL; no hardcoded
secrets; docs match code; CI green.

---

## Phase 2: Real authentication and authorization (Days 6-10)

### 2.1 Keycloak in Docker Compose
- Add `keycloak` service (`quay.io/keycloak/keycloak`, pinned version) in dev mode with a **realm import**
  file `infra/keycloak/nexusfin-realm.json` containing: realm `nexusfin`, client `nexusfin-gateway`
  (public, PKCE) and `nexusfin-service` (confidential, client-credentials), roles `CUSTOMER`, `ADMIN`, `SERVICE`,
  and two demo users (`customer1`, `admin1`) with passwords from env/documented as demo-only.
- Add a healthcheck; make the gateway depend on it.

### 2.2 Gateway as OAuth2 resource server (reactive)
- Add `spring-boot-starter-oauth2-resource-server` and `spring-boot-starter-security` to `api-gateway`.
- Replace `GatewaySecurityFilter` with a `SecurityWebFilterChain` (`ServerHttpSecurity`):
  `issuer-uri` from env, JWT signature + expiry + issuer + audience validation, public: `/actuator/health`,
  `/fallback/**`, Swagger (optional); everything else authenticated.
- Map Keycloak `realm_access.roles` to `ROLE_*` authorities with a custom `ReactiveJwtAuthenticationConverter`.
- Authorization rules: `CUSTOMER` can read own accounts and create transfers; `ADMIN` can create accounts and view
  all; `SERVICE` (client credentials) can call fraud endpoints. Document the matrix in README.
- Forward `X-User-Id` (JWT `sub`) and `X-User-Roles` downstream **only from the validated token** and strip any
  inbound spoofed `X-User-*` headers at the edge.
- Delete the hardcoded API key path and the `length > 10` check entirely. Security **enabled by default**; tests
  use a test profile with a mock JWT decoder, not a disabled filter.

### 2.3 Defense in depth in downstream services
- Add `oauth2-resource-server` to `ledger-service` (validate the same JWT) so it is not trusting the network.
- Enforce ownership: a `CUSTOMER` cannot read or move money from accounts that are not theirs
  (add `owner_id` to `accounts` via migration `V5`; seed demo accounts for the demo users).
- Use `@PreAuthorize` on controller methods.

### 2.4 Security tests
- 401 with no token, 401 with expired token, 401 with wrong issuer/signature (token signed by another key).
- 403 `CUSTOMER` calling an admin endpoint; 403 accessing someone else's account.
- 200 happy paths per role. Use Spring Security Test (`jwt()` post-processor / `WebTestClient.mutateWith`).
- One Testcontainers Keycloak integration test that fetches a real token and calls the gateway (can be limited to
  the CI e2e job to keep unit tests fast).

### 2.5 Update CI e2e job
Obtain a token from Keycloak with `curl`, then call the gateway with `Authorization: Bearer ...`. Add a negative check
(request without a token returns 401).

**Acceptance:** No endpoint reachable without a valid token; role checks proven by tests; ADR-005
("Keycloak + resource-server model") added. You can now truthfully claim Keycloak / OAuth 2.0 / OIDC / JWT / RBAC.

---

## Phase 3: Redis (Days 11-14)

### 3.1 Idempotency cache + distributed lock for hot keys
- Add `redis` (pinned image) to Compose; `spring-boot-starter-data-redis` to `ledger-service`.
- Idempotency flow: check Redis `idem:{key}` (TTL configurable, default 24h) -> on hit return stored response;
  on miss, `SET NX` a "processing" marker to block duplicate in-flight requests, run the transaction, store the
  response. **PostgreSQL unique constraint remains the source of truth**; Redis is an accelerator and must fail open
  (if Redis is down, fall back to the DB path).
- Cache account-balance reads with short TTL and **evict on posted transaction** (ensure eviction happens after
  commit via `TransactionSynchronization` / `@TransactionalEventListener(AFTER_COMMIT)`).
- Resilience: Redis timeouts/circuit breaker so slow Redis never blocks the ledger.

### 3.2 Tests (Testcontainers Redis + Postgres)
- Cache hit/miss for idempotency; TTL expiry; in-flight duplicate blocked; Redis-down fallback still correct;
  stale balance never returned after a transfer.

### 3.3 Measure
Expose Micrometer counters `idempotency.cache.hit/miss`. Report real hit ratio from the load test (Phase 7).

**Acceptance:** ADR-006 ("Redis as accelerator, DB as source of truth"); tests green.

---

## Phase 4: Real-time WebSocket/STOMP feed (Days 15-20)

### 4.1 New module `notification-service`
- Spring Boot, Eureka client, `spring-boot-starter-websocket`, Kafka consumer on `nexusfin.ledger.transactions`
  (own consumer group), JWT-validated WebSocket handshake (token via `Authorization` header or a short-lived
  query/connect-header ticket; validate on `CONNECT`).
- STOMP endpoint `/ws`; destinations: `/topic/transactions` (admin only) and `/user/queue/transactions`
  (per-customer, based on account owner).
- **Scaling story:** use Redis Pub/Sub (or a STOMP broker relay) so multiple `notification-service` instances all
  deliver to their connected clients. Kafka -> notification-service -> Redis channel -> every instance -> its
  WebSocket sessions. Document the reasoning in ADR-007.
- Gateway: add a route for `/ws/**` (WebSocket upgrade) with auth.

### 4.2 Tests
- Integration test with a STOMP client (`WebSocketStompClient`): publish a Kafka event (Testcontainers Kafka) and
  assert the subscribed client receives it; unauthorized client is rejected; customer does not receive another
  customer's events.

**Acceptance:** Live updates demonstrable end-to-end in Compose; ADR-007 written.

---

## Phase 5: Resilience and failure handling (Days 21-24)

1. **Kafka consumer retry + DLT** in `ai-fraud-service` and `notification-service`: `DefaultErrorHandler` with
   exponential backoff + `DeadLetterPublishingRecoverer` -> `nexusfin.ledger.transactions.DLT`, or `@RetryableTopic`.
   Classify non-retryable exceptions (deserialization, validation) to skip retries.
2. **DLT visibility:** a small admin endpoint or consumer that lists DLT messages count; Micrometer metric
   `dlt.messages`. Optional replay endpoint (ADMIN only).
3. **Resilience4j Retry + Bulkhead** where appropriate (gateway -> services for idempotent GETs only; never blind-retry
   money-moving POSTs unless idempotency keys are required and enforced).
4. **Test:** poison message (malformed JSON) ends in the DLT after N attempts and the consumer keeps processing
   later messages; transient failure succeeds on retry.
5. Update ADR-004 and README with the failure-handling matrix.

**Acceptance:** You can now claim DLQ/DLT, retry policies, circuit breakers, and bulkheads truthfully.

---

## Phase 6: Observability (Days 25-27)

- Micrometer + `micrometer-registry-prometheus`, `/actuator/prometheus` on every service.
- Add Prometheus and Grafana to a separate `docker-compose.observability.yml`; provision one dashboard
  (JSON committed): request rate, p95/p99 latency, error rate, outbox backlog size, DLT count, cache hit ratio.
- Distributed tracing: Micrometer Tracing + OpenTelemetry exporter (Zipkin or Tempo/Jaeger), propagate the
  existing `X-Correlation-Id` as trace baggage.
- Structured JSON logging with correlation id in MDC.

**Acceptance:** One screenshot of the dashboard and one trace saved in `docs/images/` from a real run.

---

## Phase 7: Load testing and benchmarks (Days 28-31)

- Create `perf/` with **k6** scripts: (a) transfers with unique idempotency keys, (b) opposing-pair hot accounts,
  (c) mixed read/write, (d) duplicate idempotency-key replay.
- Thresholds in script (e.g. `http_req_failed<0.01`, p95 target) so CI/local run can fail on regression.
- `docs/benchmarks/BENCHMARKS.md` must include: hardware (CPU/RAM/OS), service config (heap, DB pool size,
  virtual threads on/off), dataset size, duration, VUs, **raw k6 summary output**, and honest bottleneck analysis.
- Add a before/after comparison where you changed something (e.g. Redis on/off, pooled locking vs ordered batch lock).
- **Only these measured numbers may appear on the resume.**

---

## Phase 8: Minimal Angular dashboard (Days 32-38)

- `frontend/` Angular (latest stable), standalone components, RxJS, `angular-oauth2-oidc` (or `keycloak-js`) for
  login via Keycloak with PKCE.
- Screens: login; accounts list; "new transfer" form (reactive forms, validation, idempotency key generated
  client-side); **live transaction feed** via STOMP (`@stomp/rx-stomp`) with auto-reconnect; admin view of fraud
  decisions with risk level badges.
- HTTP interceptor for bearer token and error handling; route guards by role.
- Unit tests (Jest/Karma) for the interceptor, guard and a component; add `npm ci && npm test && npm run build` to CI.
- Add the frontend to Compose (nginx serving the build) behind the gateway.

**Acceptance:** Full flow demonstrable: login -> transfer -> live feed updates -> fraud decision visible.

---

## Phase 9: Deploy, polish, demo (Days 39-45)

1. **Cloud deployment (AWS free-tier friendly):** EC2 (Docker Compose) + RDS PostgreSQL, or ECS if you want to learn it.
   Use security groups, no public DB, secrets via SSM Parameter Store or Secrets Manager (not committed).
   Optionally add an S3 bucket use (e.g. transaction statement/CSV export written to S3) so the S3 claim is real.
2. **IaC (bonus, strong signal):** Terraform in `infra/terraform/` for VPC, EC2, RDS, security groups.
3. **CD:** GitHub Actions job to build/push images (GHCR) and deploy on tag; gate on tests.
4. **Docs:** README top section = one-paragraph pitch, architecture diagram (Mermaid or exported image),
   screenshots/GIF, "Run it in 2 commands" (`docker compose up`), live demo link (if hosted), tech table,
   benchmark summary linking to `BENCHMARKS.md`, ADR index, security model, "known limitations".
5. **Demo video (2 minutes):** login, transfer, live feed, kill a service to show circuit breaker fallback,
   show Grafana. Link it at the top of the README.
6. Add `LICENSE`, `CONTRIBUTING.md`, issue/PR templates (already present), repo topics, and a pinned repo.
7. Tag a release `v1.0.0` with release notes.

---

## Phase 10: Human-only tasks (cannot be delegated to the agent)

| Task | Why it matters | When |
|------|---------------|------|
| **AWS Developer Associate (or Solutions Architect Associate)**, 4-5 hrs/week; Phase 9 doubles as hands-on prep | Fixes your weakest credential area | Start Day 1, sit exam ~Day 60-90 |
| **2-3 technical write-ups** (dev.to/Medium/LinkedIn): "Deadlock-free double-entry ledger with ordered row locks", "Outbox pattern with Kafka: the duplicate-publish bug I found and fixed", "Making Keycloak JWT validation work in a reactive Spring Cloud Gateway" | Independent, readable proof of depth | After Phases 1, 2, 5 |
| **1 merged open-source PR** (Spring ecosystem, Keycloak docs/examples, Testcontainers, or a library you used; docs/bug fix counts) | Third-party validation | Browse "good first issue" from Day 20 |
| **Be able to explain every line**: re-implement the lock-ordering test and outbox fix from memory on paper | Interviews test understanding, not repos | Ongoing |
| Practice 5 interview stories: outbox bug, deadlock test, JWT stub -> real resource server, Redis fail-open design, DLT | Converts work into offers | Before applying |

---

## Resume mapping (fill ONLY with measured numbers)

**Do not add a bullet until its phase is merged and tested.**

- **Project: NexusFin: Event-Driven Digital Ledger** | Java 21, Spring Boot 3, Spring Cloud, Kafka, PostgreSQL,
  Redis, Keycloak, Docker, GitHub Actions, Angular  `[GitHub link] | [Live demo link]`
  - Built double-entry ledger microservices (Eureka, Gateway, Ledger, Fraud, Notification) with ordered pessimistic
    row locking; verified **[N]** concurrent opposing transfers with zero deadlocks and conserved balances using
    Testcontainers PostgreSQL. *(Phase 1)*
  - Implemented transactional outbox with `FOR UPDATE SKIP LOCKED` and Kafka at-least-once delivery with idempotent
    consumers and a dead-letter topic; fixed a duplicate-publish race found in review. *(Phases 1, 5)*
  - Secured the platform with Keycloak (OAuth 2.0/OIDC): reactive Gateway JWT validation, role- and ownership-based
    authorization, 401/403 test coverage. *(Phase 2)*
  - Added Redis idempotency/balance caching (DB remains source of truth, fails open) reaching **[X]%** hit ratio
    and p95 **[Y] ms** at **[Z] req/s** in k6 benchmarks (`docs/benchmarks`). *(Phases 3, 7)*
  - Delivered live transaction feed over WebSocket/STOMP with Redis Pub/Sub fan-out and an Angular/RxJS dashboard.
    *(Phases 4, 8)*
  - Containerized with Docker Compose, CI/CD with live e2e tests in GitHub Actions; deployed to AWS (EC2 + RDS)
    with Prometheus/Grafana monitoring. *(Phases 6, 9)*

### Fix the existing Yogada Chits bullets (separate from this repo)
Reword any claim you cannot defend there (PostgreSQL vs MySQL, microservices vs monolith, RabbitMQ, Redis,
WebSocket, Docker/CI, Resilience4j, HikariCP). Move those claims to NexusFin once true. Keep only metrics you
measured. Remove `Node.js` unless used. Skills line should only list technologies that appear in a repo.

---

## Final Definition of Done (the "9+" checklist)

- [ ] CI green on `main`: unit, integration (Testcontainers), frontend, e2e with real tokens
- [ ] No hardcoded secrets; security on by default; 401/403 tests exist
- [ ] Concurrency, idempotency and outbox race tests green on real PostgreSQL/Kafka
- [ ] Redis, STOMP, DLT, circuit breaker + retry each demonstrable and tested
- [ ] Benchmarks with raw output and hardware committed; resume numbers match them exactly
- [ ] README: architecture diagram, demo video, live link, honest limitations, ADR index
- [ ] Release `v1.0.0` tagged; repo pinned on GitHub
- [ ] AWS certification passed (or scheduled), 2+ write-ups published, 1 OSS PR submitted/merged
- [ ] Resume updated: NexusFin project, trimmed skills line, defensible Yogada bullets, GitHub/demo links near the top
- [ ] You can explain each design decision and failure mode without notes
