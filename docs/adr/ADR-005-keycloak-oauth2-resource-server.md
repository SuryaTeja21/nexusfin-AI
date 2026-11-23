# ADR-005: Keycloak OAuth 2.0 / OIDC Resource Server Architecture and Defense-in-Depth Authorization

## Status
**Accepted**

## Context & Problem Statement
Prior to this architectural enhancement, NexusFin AI utilized a naive API key mechanism (`nexusfin-prod-secret-key-2026`) checked by a custom gateway filter (`X-API-KEY` with `length > 10`). Downstream microservices (such as `ledger-service` and `ai-fraud-service`) possessed no authentication filters, trusting all incoming traffic implicitly. Furthermore, accounts lacked ownership context, meaning any authenticated client could read balances or initiate debits against any account in the ledger.

Financial transaction systems require strict security guarantees:
* Identity verification via industry-standard OpenID Connect (OIDC) and OAuth 2.0.
* Cryptographic signature verification with asymmetric key pairs (RSA/JWKS).
* Role-Based Access Control (RBAC) separating customer, administrative, and service-to-service operations.
* Zero-trust defense-in-depth where downstream services do not blindly trust the perimeter network.
* Multi-tenant account ownership isolation preventing cross-account theft and unauthorized data access.

## Decision Drivers
* Eliminate hardcoded credentials and naive perimeter-only security.
* Prevent spoofing of user identity headers (`X-User-Id`, `X-User-Roles`, `X-User-Name`).
* Ensure compliance with GAAP double-entry integrity by guaranteeing that debit operations can only be initiated by verified account owners or authorized system administrators.
* Support standard PKCE public client flows (for SPA/mobile frontends) and client credentials flows (for microservice federation).
* Maintain testability across isolated unit/integration test suites (mock JWT decoders) and live CI/CD environments (Keycloak in Docker Compose).

## Considered Options
1. **API Keys with Database Lookup**: Low complexity, but lacks user identity claims, token expiration lifecycles, asymmetric verification, and industry interoperability.
2. **Perimeter-Only JWT Validation (Gateway Token Termination)**: API Gateway validates the JWT, strips the token, and passes trusted plain HTTP headers (`X-User-Id`) to internal services. If the internal mesh is penetrated or misconfigured, downstream services have zero protection.
3. **Defense-in-Depth OAuth 2.0 Resource Server with Keycloak**:
   - Centralized Identity Provider (Keycloak 24+) managing realms, clients, roles, and users.
   - API Gateway acts as a reactive OAuth 2.0 Resource Server: validates JWT signature/issuer/expiration, strips inbound spoofed `X-User-*` headers, and relays verified claims downstream.
   - Downstream services (`ledger-service`) independently configure Spring Security OAuth 2.0 Resource Server, verifying JWT authenticity directly against JWKS or trusting gateway-verified claims with method-level authorization (`@PreAuthorize`) and domain ownership validation.

## Decision Outcome
Chosen option: **Option 3: Defense-in-Depth OAuth 2.0 Resource Server with Keycloak**.

### 1. Keycloak Realm & Client Topology
* **Realm**: `nexusfin`
* **Roles**:
  * `CUSTOMER`: Standard banking customers permitted to view their own accounts and initiate outgoing transfers.
  * `ADMIN`: Platform operators authorized to create chart of accounts and audit all transactions.
  * `SERVICE`: Microservice principals authorized for machine-to-machine APIs (e.g., AI Fraud Service).
* **Clients**:
  * `nexusfin-gateway`: Public client with PKCE (`S256`) and Direct Access Grants enabled for frontend/API interactions.
  * `nexusfin-service`: Confidential client with client credentials for internal service workloads.
* **Pre-configured Seed Users**:
  * `customer1` (`CUSTOMER` role, owner of `ACC-CUSTOMER-ALICE-USD` and `ACC-CUSTOMER-ALICE-EUR`).
  * `customer2` (`CUSTOMER` role, owner of `ACC-CUSTOMER-BOB-USD`).
  * `admin1` (`ADMIN` role, full administrative capabilities).
  * `service-account` (`SERVICE` role).

### 2. API Gateway Enforcement & Identity Relay
The API Gateway (`api-gateway`) is configured with a reactive `SecurityWebFilterChain`:
* Permits unauthenticated access to health/actuator endpoints (`/actuator/health`, `/actuator/info`).
* Requires valid Bearer JWT for all API routes (`/api/v1/**`).
* Executes `UserIdentityRelayFilter` (order `-10`):
  1. Strips any incoming spoofed `X-User-*` headers from untrusted clients.
  2. Extracts authenticated `Jwt` claims (`sub`, `preferred_username`, `realm_access.roles`).
  3. Injects sanitized `X-User-Id`, `X-User-Roles`, and `X-User-Name` headers downstream.

### 3. Downstream Defense in Depth & Multi-Tenant Account Ownership
* **Migration `V4__add_account_owner_id.sql`**: Added `owner_id VARCHAR(64)` and index `idx_accounts_owner_id` to the `accounts` table.
* **`SecurityConfig` in `ledger-service`**: Configures `JwtAuthenticationConverter` to map Keycloak realm roles (`realm_access.roles`) into Spring Security authorities (`ROLE_CUSTOMER`, `ROLE_ADMIN`, `ROLE_SERVICE`).
* **Method Security (`@PreAuthorize`)**:
  * Account creation: `hasAnyRole('ADMIN', 'SERVICE')`.
  * Account query by number: `hasAnyRole('CUSTOMER', 'ADMIN', 'SERVICE')` + domain ownership check.
  * Transfers: `hasAnyRole('CUSTOMER', 'ADMIN', 'SERVICE')`.
* **Domain Ownership Enforcement**:
  * In GAAP double-entry, a transfer debits a liability account (reducing customer balance).
  * `LedgerService` and `AccountService` verify that any leg reducing an account's balance must be owned by the authenticated caller (`jwt.getSubject()` matches `account.getOwnerId()`), unless the caller possesses `ROLE_ADMIN` or `ROLE_SERVICE`.
  * Attempting to debit an account owned by another customer aborts immediately with `403 FORBIDDEN` (`AccessDeniedException`).

## Security Guarantees & HTTP Status Codes
| Scenario | Expected HTTP Status |
| :--- | :--- |
| Missing Bearer token | `401 UNAUTHORIZED` |
| Expired or invalid signature token | `401 UNAUTHORIZED` |
| Token from unapproved issuer | `401 UNAUTHORIZED` |
| Customer calling Admin account creation | `403 FORBIDDEN` |
| Customer querying another user's account | `403 FORBIDDEN` |
| Customer attempting transfer debiting another user's account | `403 FORBIDDEN` |
| Customer querying own account or transferring own funds | `200 OK` / `201 CREATED` |
| Admin querying any account or creating accounts | `200 OK` / `201 CREATED` |

## Consequences
* **Positive**: Full zero-trust security perimeter and multi-tenant data isolation; complete elimination of static secrets; verified compliance with modern banking security standards.
* **Operational**: Keycloak container provisioned in Docker Compose; lightweight mock `JwtDecoder` beans utilized during unit/slice testing to prevent dependencies on running external identity providers during automated builds.
