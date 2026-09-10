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
    [Analytical Mart] (Dimensions & Fact Tables in PostgreSQL)
           │
           ▼
    [Power BI Service] (Cloud SaaS Hosting + Local Power BI Desktop Authoring)

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

- [x] **Phase 3: Worker Service Persistence & Migrations**
  - [x] Configure Liquibase master changelog and initial schema for `market_orders`.
  - [x] Define JPA Entity `MarketOrderEntity` and QueryDSL repository.
  - [x] Configure RabbitMQ consumer and idempotency checks for market orders.
  - [x] Comprehensive testing: Unit, Repository Slice (H2/Liquibase), and Integration tests (in-memory, Docker-free).
  - [x] Configure Spring Boot Actuator health checks (PostgreSQL & RabbitMQ liveness/readiness).

- [ ] **Phase 4: Ingestion Poller Implementation**
  - [ ] Configure `WebClient` bean with custom User-Agent (mandatory for ESI).
  - [ ] Implement `MarketOrderPoller` with region scheduling (starting with The Forge: `10000002`).
  - [ ] Integrate Resilience4j RateLimiter and error budget interceptors.
  - [ ] Publish order batches to RabbitMQ exchange.

- [ ] **Phase 5: Analytical Star Schema & Historical Batch ETL**
  - [ ] Configure Spring Batch metadata and analytical star schema tables via Liquibase:
    - Conformed Dimensions: `dim_date`, `dim_item`, `dim_region`, `dim_station`.
    - Historical Trends Fact: `fact_daily_market_history` (OHLC prices, VWAP, total turnover, order count).
    - Market Depth Fact: `fact_order_book_snapshot` (intraday order depth, liquidity, bid/ask spreads).
  - [ ] Implement daily Spring Batch aggregation jobs with idempotent chunked processing.
  - [ ] Implement historical price rollup and moving average computations.

- [ ] **Phase 6: Infrastructure, CI & Containerized Integration Testing (Deferred / Low-Priority)**
  - [ ] Configure optional Testcontainers profile for real-broker RabbitMQ and true PostgreSQL integration tests (requiring Docker / OrbStack).
  - [ ] Set up CI/CD pipeline (GitHub Actions) with automated containerized service test execution.

- [ ] **Phase 7: BI & Reporting Layer (Power BI Cloud & Multi-Model Architecture)**
  - [ ] **Authoring & Local Development:**
    - Develop and test `.pbix` models locally using Power BI Desktop against local/test PostgreSQL.
  - [ ] **Cloud Hosting (Power BI Service - `app.powerbi.com`):**
    - Publish reports and semantic models to Power BI Service cloud workspaces.
    - Configure scheduled data refreshes via On-Premises Data Gateway (for local PostgreSQL) or direct TLS (for cloud PostgreSQL).
  - [ ] **Semantic Model A: Historical Price Trends & Market Volatility:**
    - Expose multi-timeframe analytics: 7d/30d Volume Weighted Average Price (VWAP), volatility corridors, price momentum, and historical spread fluctuations.
    - DAX time-intelligence measures: Period-over-period growth, rolling standard deviations, and trend baselines.
  - [ ] **Semantic Model B: Real-Time Market Depth & Inter-Region Arbitrage:**
    - Expose live order book depth, top-of-book Best Bid/Offer (BBO) spread, and inter-hub arbitrage margins (The Forge vs. Domain/Sinq Laison/Heimatar).
    - Near-real-time slicing by station, system security rating, and trade volume tier.