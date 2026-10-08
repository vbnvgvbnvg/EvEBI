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

- [x] **Phase 4: Ingestion Poller Implementation**
  - [x] Configure `WebClient` bean with custom User-Agent (mandatory for ESI).
  - [x] Implement `MarketOrderPoller` with region scheduling (starting with The Forge: `10000002`).
  - [x] Integrate Resilience4j RateLimiter and error budget interceptors.
  - [x] Publish order batches to RabbitMQ exchange.
  - [x] **Universe-Wide Expansion & Dynamic Discovery:**
    - [x] Integrate ESI Universe API (`GET /universe/regions/`) to dynamically discover market regions.
    - [x] Filter out non-market space (wormholes `11000000+`, abyssal space `12000000+`).
    - [x] Add caching for universe region IDs to minimize overhead across hourly scraping cycles.

- [ ] **Phase 5: Analytical Star Schema & Historical Batch ETL**
  - [x] **Step 5.1: Conformed Dimension Modeling (Liquibase & Domain Entities):**
    - [x] Configure Liquibase changesets for Conformed Dimensions: `dim_date`, `dim_time`, `dim_item`, `dim_region`, `dim_station`.
    - [x] Configure staging table `market_orders` in `batch-etl` with precondition for in-memory batch reader tests.
    - [x] Define JPA Entities and QueryDSL repositories for all dimensions and staging orders.
  - [ ] **Step 5.2: Dimension Seeding, Exploration & SDE Sync Pipeline:**
    - [x] Implement `dim_date` mathematical calendar generator (2020-2035) with ISO attributes.
    - [x] Implement `dim_time` 1,440-minute clock dimension with EVE prime time buckets.
    - [x] Package curated baseline dimension seed (`dim_regions.csv`, `dim_stations.csv`, `dim_items.csv`) loaded via Liquibase `<loadData>`.
    - [x] Implement Late-Arriving Dimension resolution (`CitadelDimensionResolver`) for dynamic player Upwell Citadels.
    - [ ] Implement runtime SDE Synchronization Job/Tasklet (conditional streaming download via HTTP `ETag`/`Last-Modified`, bulk upsert into `dim_item` and `dim_station`, tracking `sde_metadata`).
  - [x] **Step 5.3: Analytical Fact Tables (Liquibase & Additive Modeling):**
    - [x] Historical Trends Fact: `fact_market_daily_summary` (OHLC prices, turnover ISK, volume traded, order count).
    - [x] Market Depth Fact: `fact_order_book_liquidity` (intraday bid/ask spreads, liquidity).
    - [x] JPA Entities (`FactMarketDailySummaryEntity`, `FactOrderBookLiquidityEntity`) and QueryDSL repositories.
    - [x] Automated integration test suite (`FactRepositoryTest`) verifying star schema persistence and QueryDSL slicing.
  - [ ] **Step 5.4: Spring Batch Pipeline & Daily ETL:**
    - [ ] Configure Spring Batch metadata repository in PostgreSQL/H2.
    - [ ] Implement daily Spring Batch aggregation jobs with idempotent chunked processing.
    - [ ] Verify Docker-free end-to-end batch execution and star schema population.

- [ ] **Phase 6: Security Hardening, Containerized Testing & CI/CD**
  - [ ] **Database & Broker Least-Privilege Hardening:**
    - [ ] Configure least-privilege PostgreSQL roles via Liquibase (`worker_dml`, `liquibase_ddl`, `powerbi_reader` read-only).
    - [ ] Configure RabbitMQ dedicated virtual host (`/eve_market`) with scoped producer write-only and consumer read-only access.
    - [ ] Enforce SSL/TLS transport encryption (`sslmode=require` for Postgres, `amqps://` for RabbitMQ).
  - [ ] **Management Endpoint & Actuator Security:**
    - [ ] Configure Spring Security to protect sensitive Actuator endpoints while preserving unauthenticated access for container health probes (`/actuator/health/liveness` and `/actuator/health/readiness`).
  - [ ] **Supply Chain & Secrets Management:**
    - [ ] Externalize all production secrets and enforce zero plain-text credential commits.
    - [ ] Integrate dependency vulnerability audits (OWASP Dependency-Check / Dependabot) in the build lifecycle.
  - [ ] **Containerized Integration Testing & CI/CD:**
    - [ ] Configure optional Testcontainers profile for real-broker RabbitMQ (with auth/vhost) and true PostgreSQL integration tests (requiring Docker / OrbStack).
    - [ ] Set up CI/CD pipeline (GitHub Actions) with automated containerized service test execution and SonarCloud quality gates.

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