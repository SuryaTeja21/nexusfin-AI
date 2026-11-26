# Security Policy

## Reporting a Vulnerability

If you discover a security vulnerability in **NexusFin AI**, please do **not** open a public GitHub issue with exploit details.

Instead, report it privately via GitHub **Security Advisories** (**Security -> Advisories -> Report a vulnerability** on this repository) or contact the maintainer directly. Please include:
- A clear description of the vulnerability and affected component/file paths.
- Reproduction steps or a minimal proof of concept.
- Potential security impact.

---

## Development Credentials Disclaimer

This repository contains **non-secret local development defaults** solely so `docker compose up -d` and automated CI tests run out of the box:
- [`infra/keycloak/nexusfin-realm.json`](infra/keycloak/nexusfin-realm.json) defines sandbox users (`admin1` / `admin_password`, `customer1` / `customer_password`) and client secret `nexusfin-service-secret`.
- [`docker-compose.yml`](docker-compose.yml) sets local fallback defaults (`POSTGRES_PASSWORD:-nexusfin_secret`, `KEYCLOAK_ADMIN:-admin`, `KEYCLOAK_ADMIN_PASSWORD:-admin`, `OPENAI_API_KEY:-dummy-key`) annotated `# local dev only`.
- [`perf/lib/common.js`](perf/lib/common.js) and [`login.component.ts`](frontend/src/app/features/login/login.component.ts) reference those local sandbox credentials for local load tests and UI testing.

**Never reuse any repository defaults in a real or internet-exposed deployment.** Use [`docker-compose.oci.yml`](docker-compose.oci.yml) / [`.env.oci.example`](.env.oci.example) (which require explicit non-default credentials), keep `.env`, `.env.oci`, `terraform.tfvars`, and `*.pem`/`*.key` files outside Git, and do not deploy `ai-fraud-service` directly on a public port without adding service-level authentication.
