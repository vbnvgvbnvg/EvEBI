# Eve-BI Architecture & Implementation Roadmap

## 1. System Architecture Overview
Eve-BI is an event-driven data pipeline designed to ingest, process, store, and analyze market order book data from EVE Online's ESI API.

    [ESI REST API] 
           │ (HTTP GET /markets/{region_id}/orders/)
           ▼
    [ingestion-poller] (Spring WebClient + Resilience4j)
           │ (JSON MarketOrderEvent)
           ▼
     [RabbitMQ Broker] (Topic Exchange: eve.market.orders)
           │
           ▼
    [worker-service] (Spring AMQP Listener)
           │ (Bulk Upsert via Spring Data JPA / QueryDSL)
           ▼
    [PostgreSQL Database] (Raw Market Orders / Changelogs via Liquibase)
           │
           ▼
    [batch-etl] (Spring Batch scheduled daily aggregations)
           │ (Daily Price History, Moving Averages, Star Schema)
           ▼
    [Analytical Mart] (Dimensions & Fact Tables)

## 2. Module Responsibilities
| Module | Packaging | Key Responsibilities |
|---|---|---|
| `esi-client` | jar | Generates WebClient API bindings from `openapi.json` |
| `ingestion-poller` | jar (boot) | Scheduled polling, ESI error-rate monitoring, AMQP publishing |
| `worker-service` | jar (boot) | Queue consumers, validation, Liquibase migrations, PostgreSQL writes |
| `batch-etl` | jar (boot) | Analytical Star Schema rollups, daily aggregations, Spring Batch jobs |

## 3. Implementation Phases

- [x] **Phase 1: Project Scaffolding & Foundation**
  - [x] Maven multi-module structure initialized.
  - [x] BOM and dependency management configured.
  - [x] OpenAPI generator compiling generated ESI client.
  - [x] Git repository configured with `.gitignore` and `.gitattributes`.

- [x] **Phase 2: Local Infrastructure Setup**
  - [x] Configure `docker-compose.yml` for PostgreSQL 16 and RabbitMQ 3 (with Management UI).
  - [x] Verify local database connectivity and RabbitMQ dashboard.

- [ ] **Phase 3: Worker Service Persistence & Migrations**
  - [ ] Configure Liquibase master changelog and initial schema for `market_orders`.
  - [ ] Define JPA Entity `MarketOrderEntity` and QueryDSL repository.
  - [ ] Configure RabbitMQ consumer and idempotency checks for market orders.

- [ ] **Phase 4: Ingestion Poller Implementation**
  - [ ] Configure `WebClient` bean with custom User-Agent (mandatory for ESI).
  - [ ] Implement `MarketOrderPoller` with region scheduling (starting with The Forge: `10000002`).
  - [ ] Integrate Resilience4j RateLimiter and error budget interceptors.
  - [ ] Publish order batches to RabbitMQ exchange.

- [ ] **Phase 5: Batch ETL Aggregations**
  - [ ] Configure Spring Batch metadata tables via Liquibase.
  - [ ] Implement daily order book snapshot and price summary aggregation jobs.