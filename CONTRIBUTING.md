# Contributing to NexusFin AI

Thanks for contributing to **NexusFin AI**. Every pull request must preserve strict double-entry accounting invariants and test coverage.

## Engineering Invariants

1. **Balanced Double-Entry**: Every transaction must verify $\sum \text{Debits} = \sum \text{Credits}$ before committing.
2. **Deadlock-Free Locking**: Multi-account locks in `ledger-service` must always acquire pessimistic row locks in ascending UUID order via `findAllByIdsWithPessimisticLock`.
3. **Outbox Integrity**: Financial domain events must be persisted in `outbox_events` within the primary database transaction and dispatched synchronously via `SKIP LOCKED`.
4. **Zero Committed Secrets**: Never commit real API keys, `.env`, `.env.oci`, `*.tfvars`, or `*.tfstate` files.
5. **Architecture Decision Records**: Significant design or security changes require an ADR under [`docs/adr/`](docs/adr/).

## Local Verification Checklist

Before opening a pull request, run:

```bash
# 1. Backend unit & Testcontainers integration suite (requires Docker)
./mvnw clean test --batch-mode

# 2. Frontend Vitest unit suite & production bundle
cd frontend && npm ci && npm test -- --watch=false && npm run build && cd ..

# 3. Compose config validation
docker compose config -q
docker compose -f docker-compose.yml -f docker-compose.observability.yml config -q
```

## Code Style

- **Backend**: Java 21, Spring Boot 3.2.x, Virtual Threads enabled, RFC 7807 `ProblemDetail` errors.
- **Frontend**: Angular 22 standalone components, strict TypeScript, reactive forms, Vitest specs (`*.spec.ts`).
