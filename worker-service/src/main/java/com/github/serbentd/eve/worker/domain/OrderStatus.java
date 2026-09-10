package com.github.serbentd.eve.worker.domain;

/**
 * Lifecycle status of an EVE Online market order within the pipeline.
 */
public enum OrderStatus {

    /**
     * Order is currently listed on the market and confirmed active by the latest ESI snapshot.
     */
    ACTIVE,

    /**
     * Order disappeared from the active snapshot prior to duration expiration (presumed fulfilled).
     */
    FINISHED,

    /**
     * Order was explicitly retracted or cancelled by the seller prior to fulfillment.
     */
    CANCELLED,

    /**
     * Order reached its maximum duration without full fulfillment (time expired).
     */
    EXPIRED
}
