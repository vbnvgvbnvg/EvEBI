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
  - [ ] **Universe-Wide Expansion & Dynamic Discovery:**
    - [ ] Integrate ESI Universe API (`GET /universe/regions/`) to dynamically discover market regions.
    - [ ] Filter out non-market space (wormholes `11000000+`, abyssal space `12000000+`).
    - [ ] Add caching for universe region IDs to minimize overhead across hourly scraping cycles.

- [ ] **Phase 5: Analytical Star Schema & Historical Batch ETL**
  - [ ] **Dimension Modeling & Static Data Seeding:**
    - [ ] Configure Liquibase changesets for Conformed Dimensions: `dim_date`, `dim_item`, `dim_region`, `dim_station`.
    - [ ] Implement dimension data seeding (enriching item names, categories, solar systems, and station names from CCP SDE / ESI Universe endpoints).
  - [ ] **Analytical Fact Tables:**
    - [ ] Historical Trends Fact: `fact_daily_market_history` (OHLC prices, VWAP, total turnover, order count).
    - [ ] Market Depth Fact: `fact_order_book_snapshot` (intraday order depth, liquidity, bid/ask spreads).
  - [ ] **Spring Batch Pipeline:**
    - [ ] Configure Spring Batch metadata repository in PostgreSQL.
    - [ ] Implement daily Spring Batch aggregation jobs with idempotent chunked processing.
    - [ ] Implement historical price rollup and moving average computations.

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