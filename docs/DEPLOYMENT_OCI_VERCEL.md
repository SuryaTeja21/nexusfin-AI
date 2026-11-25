# Hybrid Deployment Guide: Vercel Frontend + Oracle Cloud Always Free ARM64

This architecture runs the **Angular 22 UI on Vercel** and the **entire 11-container Java 21 + Kafka + PostgreSQL + Redis + Keycloak + Caddy TLS backend** on an **Oracle Cloud Infrastructure (OCI) Always Free Ampere A1 (`VM.Standard.A1.Flex`, 4 OCPUs, 24 GB RAM)** instance—at **\$0/month** without needing an AWS account.

> **Why Hybrid Instead of All-in-Vercel?**  
> Vercel hosts static/SSR frontends (`dist/frontend/browser`), whereas NexusFin AI's backend requires persistent JVM microservices, Eureka discovery, Kafka consumers, outbox polling, 2 PostgreSQL clusters, Redis, Keycloak, and long-lived STOMP WebSockets (`/ws`).

---

## Architecture Overview

```text
Browser ──(HTTPS)──▶ https://<your-app>.vercel.app (Vercel CDN + SPA rewrites)
   │
   ├──(OIDC PKCE S256)──▶ https://auth.<PUBLIC_IP>.sslip.io (Caddy TLS ──▶ Keycloak 24 + Postgres)
   └──(REST + WSS /ws)──▶ https://api.<PUBLIC_IP>.sslip.io  (Caddy TLS ──▶ API Gateway ──▶ Microservices)
```

Using **`sslip.io`** gives free DNS mapped directly to your OCI VM IP so Caddy automatically provisions **real Let's Encrypt TLS/WSS certificates** without buying a domain name (or swap in your own custom `api.` / `auth.` domains).

---

## Step 1: Provision Oracle Cloud Always Free ARM64 VM

### Option A — Automated with Terraform ([`infra/terraform/`](../infra/terraform/))
1. Sign up for Oracle Cloud Free Tier (Always Free eligible) and create an OCI API Signing Key in **Profile -> User Settings -> API Keys**.
2. Copy and configure variables:
   ```bash
   cd infra/terraform
   cp terraform.tfvars.example terraform.tfvars
   # Fill in tenancy_ocid, user_ocid, fingerprint, private_key_path, region, compartment_ocid, ssh_public_key
   ```
3. Provision the VCN, locked-down Security List (only `22`, `80`, `443` open), and `VM.Standard.A1.Flex` (`4 OCPUs`, `24 GB RAM`, Ubuntu 22.04 Minimal `aarch64`):
   ```bash
   terraform init
   terraform apply
   ```
   Terraform prints `public_ip`, `api_https_url`, and `auth_issuer_url`.

> **Tip if `Out of host capacity` appears in your home region:** Try another Availability Domain index or a 2 OCPU / 12 GB configuration first (`ocpus = 2`, `memory_in_gbs = 12`, still plenty for NexusFin), or retry after a few minutes.

### Option B — Manual OCI Console VM
Create a **Canonical Ubuntu 22.04 Minimal `aarch64`** instance with shape **`VM.Standard.A1.Flex`** (`4 OCPUs`, `24 GB RAM`), open VCN ingress ports **`80`** and **`443`** (never open `5432`, `5433`, `6379`, or `9092`), and run [`infra/terraform/cloud-init.yaml`](../infra/terraform/cloud-init.yaml) steps to install Docker + open host `iptables`.

---

## Step 2: Start Backend Services on the OCI VM

1. SSH into the ARM64 VM (`ssh ubuntu@<PUBLIC_IP>`):
   ```bash
   sudo chown -R ubuntu:ubuntu /opt/nexusfin
   git clone <YOUR_GITHUB_REPO_URL> /opt/nexusfin
   cd /opt/nexusfin
   ```
2. Install JDK 21 (only needed if building JARs directly on VM instead of pulling prebuilt multi-arch GHCR images from `.github/workflows/cd.yml`):
   ```bash
   sudo apt-get update && sudo apt-get install -y openjdk-21-jdk-headless
   ./mvnw clean package -DskipTests --batch-mode
   ```
3. Configure `.env.oci`:
   ```bash
   cp .env.oci.example .env.oci
   # Replace <OCI_PUBLIC_IP>, POSTGRES_PASSWORD, KEYCLOAK_ADMIN_PASSWORD, and VERCEL_APP_URL
   ```
4. Launch the production ARM64 + Caddy TLS stack:
   ```bash
   docker compose --env-file .env.oci -f docker-compose.yml -f docker-compose.oci.yml up -d --build
   ```
5. Verify Caddy TLS and health endpoints:
   ```bash
   curl -sf https://api.<PUBLIC_IP>.sslip.io/actuator/health
   curl -sf https://auth.<PUBLIC_IP>.sslip.io/realms/nexusfin | head -c 200
   ```

---

## Step 3: Deploy Angular Dashboard to Vercel

1. Import your GitHub repository in **Vercel -> Add New Project**.
2. Set **Root Directory** to **`frontend`** (Vercel automatically uses [`frontend/vercel.json`](../frontend/vercel.json)).
3. Under **Environment Variables**, set:
   - `NEXUSFIN_GATEWAY_URL` = `https://api.<PUBLIC_IP>.sslip.io`
   - `NEXUSFIN_KEYCLOAK_ISSUER_URL` = `https://auth.<PUBLIC_IP>.sslip.io/realms/nexusfin`
4. Click **Deploy**. Vercel runs `npm run build:vercel`, which injects `nexusfin-config.js` into `dist/frontend/browser`.

---

## Step 4: Register Your Exact Vercel URL in Keycloak

Keycloak 24 does not permit wildcard subdomains (`https://*.vercel.app/*`) inside `redirectUris`. Register your exact live Vercel URL (`https://your-project.vercel.app`) once by running:

```bash
AUTH_DOMAIN=auth.<PUBLIC_IP>.sslip.io \
KEYCLOAK_ADMIN=admin \
KEYCLOAK_ADMIN_PASSWORD=<YOUR_KEYCLOAK_ADMIN_PASSWORD> \
VERCEL_APP_URL=https://your-project.vercel.app \
  ./infra/oci/register-vercel-redirect.sh
```

Then tighten `NEXUSFIN_CORS_ALLOWED_ORIGIN_PATTERNS=https://your-project.vercel.app` inside `.env.oci` and recreate `api-gateway`:
```bash
docker compose --env-file .env.oci -f docker-compose.yml -f docker-compose.oci.yml up -d api-gateway
```

---

## Step 5: Smoke-Test Production End-to-End

1. Open `https://your-project.vercel.app`.
2. Click **Sign In with Keycloak** and authenticate via PKCE (`admin1` / `admin_password`).
3. Create two accounts on **Accounts**, open **Live Feed** (confirm green **CONNECTED** badge over `wss://.../ws`), submit a transfer on **New Transfer**, and inspect the live feed + **Fraud Console** decision.
