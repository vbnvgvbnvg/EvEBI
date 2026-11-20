#!/bin/sh
set -e

echo "Initializing PostgreSQL least-privilege database roles and users..."

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    DO \$\$
    BEGIN
        -- 1. Dedicated Liquibase DDL Migration Role / User
        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = '${POSTGRES_LIQUIBASE_USER:-liquibase_ddl}') THEN
            CREATE USER ${POSTGRES_LIQUIBASE_USER:-liquibase_ddl} WITH PASSWORD '${POSTGRES_LIQUIBASE_PASSWORD:-eve_password}';
        ELSE
            ALTER USER ${POSTGRES_LIQUIBASE_USER:-liquibase_ddl} WITH PASSWORD '${POSTGRES_LIQUIBASE_PASSWORD:-eve_password}';
        END IF;

        -- 2. Operational Ingestion Worker DML Role / User (staging write-only)
        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = '${POSTGRES_WORKER_USER:-worker_dml}') THEN
            CREATE USER ${POSTGRES_WORKER_USER:-worker_dml} WITH PASSWORD '${POSTGRES_WORKER_PASSWORD:-eve_password}';
        ELSE
            ALTER USER ${POSTGRES_WORKER_USER:-worker_dml} WITH PASSWORD '${POSTGRES_WORKER_PASSWORD:-eve_password}';
        END IF;

        -- 3. Analytical Batch ETL DML Role / User (staging read-only, analytics & batch read-write)
        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = '${POSTGRES_BATCH_USER:-batch_dml}') THEN
            CREATE USER ${POSTGRES_BATCH_USER:-batch_dml} WITH PASSWORD '${POSTGRES_BATCH_PASSWORD:-eve_password}';
        ELSE
            ALTER USER ${POSTGRES_BATCH_USER:-batch_dml} WITH PASSWORD '${POSTGRES_BATCH_PASSWORD:-eve_password}';
        END IF;

        -- 4. Read-Only Power BI Analytical Mart Role / User
        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = '${POSTGRES_POWERBI_USER:-powerbi_reader}') THEN
            CREATE USER ${POSTGRES_POWERBI_USER:-powerbi_reader} WITH PASSWORD '${POSTGRES_POWERBI_PASSWORD:-eve_password}';
        ELSE
            ALTER USER ${POSTGRES_POWERBI_USER:-powerbi_reader} WITH PASSWORD '${POSTGRES_POWERBI_PASSWORD:-eve_password}';
        END IF;

        -- Lock down default public schema and isolate Liquibase tracking tables in dedicated liquibase schema
        REVOKE ALL ON SCHEMA public FROM PUBLIC;
        GRANT CREATE ON DATABASE "${POSTGRES_DB}" TO ${POSTGRES_LIQUIBASE_USER:-liquibase_ddl};
        CREATE SCHEMA IF NOT EXISTS liquibase AUTHORIZATION ${POSTGRES_LIQUIBASE_USER:-liquibase_ddl};
        REVOKE ALL ON SCHEMA liquibase FROM PUBLIC;
        GRANT USAGE, CREATE ON SCHEMA liquibase TO ${POSTGRES_LIQUIBASE_USER:-liquibase_ddl};
    END
    \$\$;
EOSQL

echo "PostgreSQL least-privilege roles initialized successfully."
