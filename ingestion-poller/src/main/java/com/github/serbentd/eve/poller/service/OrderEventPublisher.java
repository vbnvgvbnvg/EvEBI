package com.github.serbentd.eve.poller.service;

import com.github.serbentd.eve.poller.config.PollerProperties;
import com.github.serbentd.eve.poller.event.MarketOrderEvent;
import com.github.serbentd.eve.poller.event.RegionSnapshotCompletedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Service responsible for publishing market order snapshot events to RabbitMQ.
 */
@Slf4j
@Service
public class OrderEventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final PollerProperties properties;

    public OrderEventPublisher(RabbitTemplate rabbitTemplate, PollerProperties properties) {
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
    }

    /**
     * Publishes a batch of market order events to the configured AMQP topic exchange.
     *
     * @param events list of market order events to publish
     */
    public void publishOrderBatch(List<MarketOrderEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }

        String exchange = properties.amqp().exchange();
        String routingKey = properties.amqp().routingKey();

        log.info("Publishing batch of {} market order events to exchange '{}' with routing key '{}'",
                events.size(), exchange, routingKey);
        rabbitTemplate.convertAndSend(exchange, routingKey, events);
    }

    /**
     * Publishes a region snapshot completion signal to the configured AMQP topic exchange.
     *
     * @param event snapshot completion event
     */
    public void publishSnapshotCompleted(RegionSnapshotCompletedEvent event) {
        if (event == null) {
            return;
        }

        String exchange = properties.amqp().exchange();
        String routingKey = properties.amqp().routingKey();

        log.info("Publishing region snapshot completed event for region {} (snapshot: {}, total orders: {})",
                event.regionId(), event.snapshotId(), event.totalOrders());
        rabbitTemplate.convertAndSend(exchange, routingKey, event);
    }
}
