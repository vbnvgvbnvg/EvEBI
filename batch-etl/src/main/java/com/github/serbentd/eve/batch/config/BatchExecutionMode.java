package com.github.serbentd.eve.batch.config;

/**
 * Operational execution mode for the Spring Batch ETL service.
 * <p>
 * Controls whether the application acts as a continuous daemon (supporting REST triggers,
 * internal scheduling, and remote-partitioning AMQP consumption) or executes a specific
 * finite job to completion as an ephemeral Kubernetes CronJob before exiting.
 */
public enum BatchExecutionMode {

    /**
     * Long-running daemon process mode (default).
     * Listens for REST API triggers, runs internal cron schedules (if enabled),
     * and consumes partition requests if configured in WORKER role.
     */
    DAEMON,

    /**
     * One-shot Kubernetes CronJob mode executing {@code sdeSyncJob} once on startup and terminating.
     */
    SDE_SYNC,

    /**
     * One-shot Kubernetes CronJob mode executing {@code dailyMarketSummaryJob} once on startup and terminating.
     */
    DAILY_MARKET_SUMMARY,

    /**
     * One-shot Kubernetes CronJob mode executing {@code orderBookLiquidityJob} once on startup and terminating.
     */
    ORDER_BOOK_LIQUIDITY
}
