# ADR-007: Real-Time WebSocket/STOMP Feed with Redis Pub/Sub Cluster Fan-Out

## Status
**Accepted**

## Context & Problem Statement
Financial applications require real-time notifications for transaction settlements, account balances, and security events. Continual polling of REST endpoints creates unnecessary network traffic, database query overhead, and noticeable notification latency.

In enterprise cloud environments, the notification layer runs across multiple horizontal instances behind a load balancer / API Gateway. This introduces a core distributed systems challenge:
1. Client WebSocket connections are stateful and terminate at specific service instances (e.g., Alice connects to Instance 1, Bob connects to Instance 2).
2. Kafka consumer groups assign partitions among consumers. When a transaction event is published to `nexusfin.ledger.transactions`, only **one** instance of the notification consumer group consumes that partition.
3. If Instance 1 consumes an event affecting Bob, but Bob's WebSocket session is on Instance 2, Instance 1 cannot deliver the message locally.

## Decision Drivers
* Sub-second push delivery of double-entry ledger transactions to end-users and administrative consoles.
* Standardized, cross-platform protocol (WebSocket + STOMP) compatible with web, mobile, and desktop clients.
* Strict identity and access control:
  * STOMP `CONNECT` frames must be cryptographically validated with Bearer JWT tokens.
  * Destination `/topic/transactions` must be restricted exclusively to `ADMIN` and `SERVICE` principals.
  * Destination `/user/queue/transactions` must guarantee multi-tenant privacy: customers must only receive notifications for transaction legs affecting accounts they own.
* Linear horizontal scalability: Adding new instances of `notification-service` must scale WebSocket connection capacity without partitioning issues or dropped notifications.

## Considered Options
1. **Client HTTP Polling**: Simple, but inefficient, high latency, and strains backend database connection pools.
2. **Dedicated External STOMP Message Broker (e.g., RabbitMQ Web-STOMP)**: Introduces additional operational complexity, configuration management, and infrastructure cost.
3. **Hybrid Kafka Ingestion + Redis Pub/Sub Inter-Node Fan-Out**:
   - `notification-service` consumer group consumes from Kafka topic `nexusfin.ledger.transactions`.
   - The consuming node publishes the transaction JSON to a shared Redis Pub/Sub channel (`nexusfin.notifications.transactions`).
   - Every active `notification-service` instance in the cluster subscribes to this Redis channel via a lightweight `RedisMessageListenerContainer`.
   - Each instance receives the broadcast and routes it to its locally connected WebSocket sessions:
     - Admins on `/topic/transactions`.
     - Customers on `/user/{ownerId}/queue/transactions`.

## Decision Outcome
Chosen option: **Option 3: Hybrid Kafka Ingestion + Redis Pub/Sub Inter-Node Fan-Out**.

```
+----------------+       Kafka        +-----------------------------------------------+
| ledger-service | =================> | notification-service (Cluster Consumer Group) |
+----------------+                    +-----------------------------------------------+
                                                             |
                                                   Publishes to Redis Channel
                                                             v
                                           +-----------------------------------+
                                           | Redis Pub/Sub:                    |
                                           | nexusfin.notifications.transact.. |
                                           +-----------------------------------+
                                                     |               |
                                      Broadcasts to  |               |  Broadcasts to
                                      all nodes      v               v  all nodes
                        +------------------------------+   +------------------------------+
                        | notification-service (Node 1)|   | notification-service (Node 2)|
                        +------------------------------+   +------------------------------+
                                       |                                  |
                               WebSocket STOMP                    WebSocket STOMP
                                       v                                  v
                               [Admin Console]                    [Customer Wallet]
```

### Security Architecture
1. **Edge Routing**: Spring Cloud Gateway exposes `/ws/**` with `lb:ws://notification-service` allowing the HTTP 101 WebSocket Upgrade.
2. **Frame Authentication**: In `WebSocketConfig`, a custom `ChannelInterceptor` inspects inbound `CONNECT` frames, extracts the `Authorization: Bearer <jwt>` header, validates the signature via `JwtDecoder`, and attaches the authenticated principal to the session.
3. **Destination Authorization**:
   - On `SUBSCRIBE` to `/topic/transactions`, the interceptor verifies `ROLE_ADMIN`. Non-admin subscriptions are rejected with `AccessDeniedException`.
   - On `SUBSCRIBE` to `/user/queue/transactions`, Spring's `UserDestinationMessageHandler` binds the private queue strictly to the authenticated `sub` / `username`.

## Consequences
* **Positive**: Clean separation of concerns; Kafka guarantees durability and ordering; Redis Pub/Sub provides sub-millisecond inter-node fan-out without state persistence; zero message loss across cluster nodes.
* **Operational**: New standalone module `notification-service` (Port 8084) provisioned in Docker Compose and Eureka discovery registry.
