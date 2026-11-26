# NexusFin: Local Development & Contribution Guide

This guide covers setting up your development environment, running tests, and launching the full microservices stack.

---

## 1. Prerequisites

### Option A: VS Code Dev Containers / GitHub Codespaces (Recommended)
The repository includes a complete development container configuration in `.devcontainer/devcontainer.json`:
- **Base image:** Debian Bookworm with OpenJDK 21 (`mcr.microsoft.com/devcontainers/java:1-21-bookworm`)
- **Pre-installed tools:** JDK 21, Docker-in-Docker / Buildx, Maven wrapper
- **Automated setup:** Runs `chmod +x mvnw` on container creation and forwards core service ports in `.devcontainer/devcontainer.json` (`8080`, `8081`, `8082`, `8761`, `9092`; add `8083`/`8084` manually if running Keycloak or `notification-service` inside Dev Containers).

### Option B: Local Environment
If developing directly on your host machine:
1. **Java Development Kit (JDK 21):**
   - Install Eclipse Temurin 21 or OpenJDK 21.
   - Using SDKMAN: `sdk install java 21-tem`
   - Using Homebrew (macOS): `brew install openjdk@21`
   - Verify: `java -version` (must report version 21.x)
2. **Docker & Docker Compose:**
   - Docker Desktop or OrbStack with Docker Compose v2.
   - Required for Testcontainers (PostgreSQL 16, Apache Kafka 3.7, Redis 7) during integration tests and for running the local stack.
   - Verify: `docker --version && docker compose version`

---

## 2. Configuration & Environment Variables

Copy the example environment file:
```bash
cp .env.example .env
```

Ensure placeholders in `.env` are set appropriately for your local environment. Secrets should never be committed into source control.

---

## 3. Building and Running Tests

The project is structured as a Maven multi-module repository with 5 Spring Boot modules (plus the `frontend/` Angular 22 SPA):
- `discovery-server` (Netflix Eureka Server, `:8761`)
- `api-gateway` (Spring Cloud Gateway WebFlux, `:8080`)
- `ledger-service` (Spring Boot + PostgreSQL + Kafka Outbox + Redis, `:8081`)
- `ai-fraud-service` (Spring Boot + Kafka Consumer + Spring AI, `:8082`)
- `notification-service` (Spring Boot + STOMP WebSocket + Redis Pub/Sub, `:8084`)

### Run Tests
Surefire in the parent `pom.xml` includes `*Test.java`, `*Tests.java`, and `*IT.java`, so full module runs require Docker for Testcontainers:
```bash
./mvnw clean test
```

To run tests for a specific module:
```bash
./mvnw test -pl ledger-service
```

### Run Full Maven Verification (Including Packaging)
Requires Docker running on the host or in Dev Container:
```bash
./mvnw verify
```

To verify a specific module:
```bash
./mvnw verify -pl ledger-service
```

---

## 4. Running the Full Stack Locally

### 1. Start Infrastructure (PostgreSQL, Kafka, Redis, Keycloak)
```bash
docker compose up -d postgres-ledger postgres-vector kafka redis keycloak
```

### 2. Launch Services in Sequence
Start services in the following order:

1. **Discovery Server:**
   ```bash
   ./mvnw spring-boot:run -pl discovery-server
   ```
   *Dashboard:* http://localhost:8761

2. **API Gateway:**
   ```bash
   ./mvnw spring-boot:run -pl api-gateway
   ```
   *Gateway Port:* http://localhost:8080

3. **Ledger Service:**
   ```bash
   ./mvnw spring-boot:run -pl ledger-service
   ```
   *Port:* http://localhost:8081

4. **AI Fraud Service:**
   ```bash
   ./mvnw spring-boot:run -pl ai-fraud-service
   ```
   *Port:* http://localhost:8082

5. **Notification Service:**
   ```bash
   ./mvnw spring-boot:run -pl notification-service
   ```
   *Port:* http://localhost:8084

### Or Launch Entire Stack via Docker Compose
```bash
docker compose up --build -d
```

---

## 5. Health Checks & Verification

- **Eureka Registry:** `curl http://localhost:8761`
- **Gateway Health:** `curl http://localhost:8080/actuator/health`
- **Ledger Health:** `curl http://localhost:8081/actuator/health`
- **Fraud Service Health:** `curl http://localhost:8082/actuator/health`
- **Notification Service Health:** `curl http://localhost:8084/actuator/health`

---

## 6. Code Style & Commit Conventions

- Use **Conventional Commits** (`feat:`, `fix:`, `test:`, `docs:`, `chore:`, `ci:`).
- Always ensure all tests pass (`./mvnw clean verify`) before committing.
