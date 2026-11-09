package com.github.serbentd.eve.batch.config;

/**
 * Operational role for Spring Batch ETL execution nodes.
 */
public enum PartitionRole {
    /**
     * Executes batch steps in a single JVM process without remote partitioning.
     */
    STANDALONE,

    /**
     * Orchestrates batch jobs, generates region partitions, and dispatches requests over AMQP.
     */
    MANAGER,

    /**
     * Consumes partition requests from AMQP and executes worker steps against assigned regions.
     */
    WORKER
}
