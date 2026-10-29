# ADR-004: API Gateway Edge Resilience & Perimeter Security

## Status
**Accepted**

## Context & Problem Statement
As an enterprise FinTech platform, NexusFin AI must protect internal microservices from cascading failures, unpredictable latency spikes, and unauthorized edge access.
If `ledger-service` or `ai-fraud-service` experiences high load or downtime, unmitigated gateway traffic will exhaust thread pools and resources across upstream callers.

## Decision Drivers
* Isolate service failures and prevent cascading latency.
* Return standardized RFC 7807 Problem Details on failures instead of generic 500 errors.
* Enforce edge perimeter authentication (API Keys & JWT tokens) without coupling to downstream services.
* Guarantee end-to-end distributed tracing across all HTTP hops via `X-Correlation-Id`.

## Decision Outcome
Chosen architecture:
1. **Resilience4j Reactive Circuit Breakers**: Attached to `/api/v1/accounts/**`, `/api/v1/ledger/**`, and `/api/v1/fraud/**` routes with a 5-second timeout and 50% failure rate trip threshold.
2. **Standardized Fallbacks**: Degraded requests are forwarded to `/fallback/ledger` or `/fallback/fraud` returning HTTP 503 Problem Details.
3. **Correlation ID Global Filter**: Generates or propagates immutable `X-Correlation-Id` across all hops.
4. **Gateway Security Filter**: Edge authentication filter inspecting `X-API-Key` or `Authorization: Bearer <token>` while whitelisting `/fallback/**`, `/actuator/**`, and `/swagger-ui/**`.

## Positive Consequences
* Downstream services are protected from thundering herds during outages.
* Clients receive immediate, machine-readable Problem Details during degradation.
* Audit logs maintain full traceability across the microservice topology.
