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

        -- 2. Operational Application Worker DML Role / User
        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = '${POSTGRES_WORKER_USER:-worker_dml}') THEN
            CREATE USER ${POSTGRES_WORKER_USER:-worker_dml} WITH PASSWORD '${POSTGRES_WORKER_PASSWORD:-eve_password}';
        ELSE
            ALTER USER ${POSTGRES_WORKER_USER:-worker_dml} WITH PASSWORD '${POSTGRES_WORKER_PASSWORD:-eve_password}';
        END IF;

        -- 3. Read-Only Power BI Analytical Mart Role / User
        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = '${POSTGRES_POWERBI_USER:-powerbi_reader}') THEN
            CREATE USER ${POSTGRES_POWERBI_USER:-powerbi_reader} WITH PASSWORD '${POSTGRES_POWERBI_PASSWORD:-eve_password}';
        ELSE
            ALTER USER ${POSTGRES_POWERBI_USER:-powerbi_reader} WITH PASSWORD '${POSTGRES_POWERBI_PASSWORD:-eve_password}';
        END IF;

        -- Ensure liquibase_ddl has permissions to create schemas in eve_bi
        GRANT CREATE ON DATABASE "${POSTGRES_DB}" TO ${POSTGRES_LIQUIBASE_USER:-liquibase_ddl};
    END
    \$\$;
EOSQL

echo "PostgreSQL least-privilege roles initialized successfully."
