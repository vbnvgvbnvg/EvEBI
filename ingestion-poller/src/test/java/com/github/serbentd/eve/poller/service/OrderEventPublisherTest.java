package com.github.serbentd.eve.poller.service;

import com.github.serbentd.eve.poller.config.PollerProperties;
import com.github.serbentd.eve.poller.event.MarketOrderEvent;
import com.github.serbentd.eve.poller.event.RegionSnapshotCompletedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.util.unit.DataSize;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class OrderEventPublisherTest {

    private static final Long THE_FORGE_REGION_ID = 10000002L;
    private static final Long JITA_SYSTEM_ID = 30000142L;
    private static final Long JITA_4_4_STATION_ID = 60003760L;
    private static final String TEST_EXCHANGE = "test.market.orders";
    private static final String TEST_ROUTING_KEY = "test.market.orders.raw";

    @Mock
    private RabbitTemplate rabbitTemplate;

    private OrderEventPublisher publisher;

    @BeforeEach
    void setUp() {
        PollerProperties.EsiProperties esi = new PollerProperties.EsiProperties(
                "https://esi.evetech.net",
                "EvEBI-Test/1.0",
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                DataSize.ofMegabytes(1),
                20
        );
        PollerProperties.SchedulingProperties scheduling = new PollerProperties.SchedulingProperties(
                List.of(THE_FORGE_REGION_ID),
                Duration.ofHours(1),
                Duration.ofHours(1)
        );
        PollerProperties.AmqpProperties amqp = new PollerProperties.AmqpProperties(
                TEST_EXCHANGE,
                TEST_ROUTING_KEY
        );
        PollerProperties properties = new PollerProperties(esi, scheduling, amqp);
        publisher = new OrderEventPublisher(rabbitTemplate, properties);
    }

    @Test
    void publishOrderBatch_withValidEvents_shouldPublishToExchange() {
        OffsetDateTime now = OffsetDateTime.now();
        List<MarketOrderEvent> events = List.of(
                createSampleEvent(1001L, 34L, 15.5, 50L, now),
                createSampleEvent(1002L, 34L, 16.0, 100L, now)
        );

        publisher.publishOrderBatch(events);

        verify(rabbitTemplate).convertAndSend(TEST_EXCHANGE, TEST_ROUTING_KEY, events);
    }

    @Test
    void publishOrderBatch_whenEventsNullOrEmpty_shouldDoNothing() {
        publisher.publishOrderBatch(null);
        publisher.publishOrderBatch(List.of());

        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void publishSnapshotCompleted_withValidEvent_shouldPublishToExchange() {
        OffsetDateTime completedAt = OffsetDateTime.now();
        RegionSnapshotCompletedEvent event = new RegionSnapshotCompletedEvent(
                THE_FORGE_REGION_ID,
                "snap-12345",
                500,
                5,
                completedAt
        );

        publisher.publishSnapshotCompleted(event);

        verify(rabbitTemplate).convertAndSend(TEST_EXCHANGE, TEST_ROUTING_KEY, event);
    }

    @Test
    void publishSnapshotCompleted_whenEventNull_shouldDoNothing() {
        publisher.publishSnapshotCompleted(null);

        verifyNoInteractions(rabbitTemplate);
    }

    private MarketOrderEvent createSampleEvent(Long orderId, Long typeId, Double price, Long volumeRemain, OffsetDateTime now) {
        return new MarketOrderEvent(
                orderId,
                typeId,
                THE_FORGE_REGION_ID,
                JITA_SYSTEM_ID,
                JITA_4_4_STATION_ID,
                BigDecimal.valueOf(price),
                volumeRemain,
                100L,
                1,
                90,
                false,
                "region",
                "snap-12345",
                now,
                now
        );
    }
}
