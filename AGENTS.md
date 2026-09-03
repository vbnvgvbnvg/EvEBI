# Project Context: EVE Online Data Pipeline

## Architecture Overview
This is a multi-module Spring Boot application designed to ingest, process, and analyze EVE Online virtual economy data.
*   **Database:** PostgreSQL (Operational 3NF and Analytical Star Schema).
*   **Message Broker:** RabbitMQ for decoupled, high-throughput ingestion.
*   **Deployment:** Containerized via Docker, orchestrated via AWS ECS/Fargate.

## AI Coding Guidelines
1.  **Contract-First:** Do not hand-code external API clients. Rely on the `openapi-generator-maven-plugin` using the ESI Swagger specification.
2.  **Concurrency:** Use reactive Spring `WebClient` for asynchronous pagination fetching.
3.  **Resiliency:** Implement `Resilience4j` circuit breakers on all external ESI calls.
4.  **Database Operations:** Use bulk `INSERT ON CONFLICT DO UPDATE` (Upserts) rather than row-by-row JPA saves for high-throughput ingestion.
5.  **Build Tool:** Maven multi-module structure. Java 21, Spring Boot 3.x.