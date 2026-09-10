package com.github.serbentd.eve.worker.listener;

import com.github.serbentd.eve.worker.event.MarketOrderEvent;
import com.github.serbentd.eve.worker.event.RegionSnapshotCompletedEvent;
import com.github.serbentd.eve.worker.service.OrderPersistenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * AMQP message consumer that listens to market order and snapshot events.
 * Delegates batch persistence and snapshot reconciliation to {@link OrderPersistenceService}.
 */
@Slf4j
@Component
@RabbitListener(queues = "${eve.market.queue}")
@RequiredArgsConstructor
public class OrderPersistenceListener {

    private final OrderPersistenceService orderPersistenceService;

    /**
     * Consumes a batch of market orders for persistence.
     *
     * @param events list of market order events to persist
     */
    @RabbitHandler
    public void handleOrderEvents(List<MarketOrderEvent> events) {
        log.info("Received batch of {} market order events from AMQP queue", events.size());
        orderPersistenceService.persistOrders(events);
    }

    /**
     * Consumes a single market order event for persistence (if delivered individually).
     *
     * @param event individual market order event
     */
    @RabbitHandler
    public void handleSingleOrderEvent(MarketOrderEvent event) {
        log.debug("Received single market order event: orderId={}", event.orderId());
        orderPersistenceService.persistOrders(List.of(event));
    }

    /**
     * Consumes a region snapshot completion signal and triggers reconciliation.
     *
     * @param event region snapshot completion event
     */
    @RabbitHandler
    public void handleSnapshotCompleted(RegionSnapshotCompletedEvent event) {
        log.info("Received region snapshot completed event for region: {}, snapshot: {}",
                event.regionId(), event.snapshotId());
        int finishedOrders = orderPersistenceService.reconcileSnapshot(event);
        log.info("Reconciled region {} snapshot {}: {} stale orders marked as FINISHED",
                event.regionId(), event.snapshotId(), finishedOrders);
    }
}
