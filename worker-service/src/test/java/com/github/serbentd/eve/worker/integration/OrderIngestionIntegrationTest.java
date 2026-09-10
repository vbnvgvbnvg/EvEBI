package com.github.serbentd.eve.worker.integration;

import com.github.serbentd.eve.worker.domain.MarketOrderEntity;
import com.github.serbentd.eve.worker.domain.OrderStatus;
import com.github.serbentd.eve.worker.event.MarketOrderEvent;
import com.github.serbentd.eve.worker.event.RegionSnapshotCompletedEvent;
import com.github.serbentd.eve.worker.listener.OrderPersistenceListener;
import com.github.serbentd.eve.worker.repository.MarketOrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.SmartMessageConverter;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:order_ingestion_test;MODE=PostgreSQL;DEFAULT_NULL_ORDERING=HIGH",
        "spring.rabbitmq.listener.simple.auto-startup=false"
})
@Transactional
class OrderIngestionIntegrationTest {

    private final OrderPersistenceListener listener;
    private final MarketOrderRepository repository;
    private final SmartMessageConverter messageConverter;

    OrderIngestionIntegrationTest(
            OrderPersistenceListener listener,
            MarketOrderRepository repository,
            SmartMessageConverter messageConverter
    ) {
        this.listener = listener;
        this.repository = repository;
        this.messageConverter = messageConverter;
    }

    @Test
    void ingestOrderBatch_shouldDeserializeAndPersistToDatabase() {
        OffsetDateTime now = OffsetDateTime.now();
        List<MarketOrderEvent> events = List.of(
                createSampleEvent(1001L, 34L, 10.50, 50L, "snap-batch-1", now),
                createSampleEvent(1002L, 34L, 11.00, 100L, "snap-batch-1", now)
        );

        // 1. Verify JSON serialization via configured Jackson2JsonMessageConverter
        MessageProperties properties = new MessageProperties();
        Message message = messageConverter.toMessage(events, properties);
        String jsonPayload = new String(message.getBody(), StandardCharsets.UTF_8);
        assertThat(jsonPayload).contains("1001").contains("1002");

        // 2. Verify deserialization back to domain events using conversion hint
        @SuppressWarnings("unchecked")
        List<MarketOrderEvent> deserializedEvents = (List<MarketOrderEvent>) messageConverter.fromMessage(
                message,
                new ParameterizedTypeReference<List<MarketOrderEvent>>() {}
        );
        assertThat(deserializedEvents).hasSize(2);

        // 3. Process through the listener into the database
        listener.handleOrderEvents(deserializedEvents);

        // 4. Verify persistence in the database
        Optional<MarketOrderEntity> order1 = repository.findById(1001L);
        assertThat(order1).isPresent();
        assertThat(order1.get().getPrice()).isEqualByComparingTo(BigDecimal.valueOf(10.50));
        assertThat(order1.get().getStatus()).isEqualTo(OrderStatus.ACTIVE);
        assertThat(order1.get().getIsActive()).isTrue();
        assertThat(order1.get().getSnapshotId()).isEqualTo("snap-batch-1");

        Optional<MarketOrderEntity> order2 = repository.findById(1002L);
        assertThat(order2).isPresent();
        assertThat(order2.get().getPrice()).isEqualByComparingTo(BigDecimal.valueOf(11.00));
        assertThat(order2.get().getStatus()).isEqualTo(OrderStatus.ACTIVE);
        assertThat(order2.get().getIsActive()).isTrue();
    }

    @Test
    void ingestSnapshotAndReconcile_shouldMarkDisappearedOrdersAsFinished() {
        OffsetDateTime t1 = OffsetDateTime.now().minusMinutes(10);
        OffsetDateTime t2 = OffsetDateTime.now();

        // 1. First snapshot discovers orders 2001, 2002, 2003
        List<MarketOrderEvent> snapshot1Orders = List.of(
                createSampleEvent(2001L, 34L, 100.0, 10L, "snap-1", t1),
                createSampleEvent(2002L, 34L, 105.0, 20L, "snap-1", t1),
                createSampleEvent(2003L, 34L, 110.0, 30L, "snap-1", t1)
        );
        listener.handleOrderEvents(snapshot1Orders);

        assertThat(repository.findByRegionId(10000002L)).hasSize(3);

        // 2. Second snapshot only includes 2001 and 2002 (order 2003 was fulfilled/cancelled)
        List<MarketOrderEvent> snapshot2Orders = List.of(
                createSampleEvent(2001L, 34L, 99.0, 8L, "snap-2", t2),
                createSampleEvent(2002L, 34L, 105.0, 20L, "snap-2", t2)
        );
        listener.handleOrderEvents(snapshot2Orders);

        // 3. Poller completes polling all pages for snapshot 2 and sends completion event
        RegionSnapshotCompletedEvent completionEvent = new RegionSnapshotCompletedEvent(
                10000002L,
                "snap-2",
                2,
                1,
                t2
        );
        listener.handleSnapshotCompleted(completionEvent);

        // 4. Verify orders 2001 and 2002 are updated and ACTIVE with snap-2
        MarketOrderEntity order2001 = repository.findById(2001L).orElseThrow();
        assertThat(order2001.getStatus()).isEqualTo(OrderStatus.ACTIVE);
        assertThat(order2001.getIsActive()).isTrue();
        assertThat(order2001.getPrice()).isEqualByComparingTo(BigDecimal.valueOf(99.0));
        assertThat(order2001.getSnapshotId()).isEqualTo("snap-2");

        // 5. Verify order 2003 was automatically transitioned to FINISHED
        MarketOrderEntity order2003 = repository.findById(2003L).orElseThrow();
        assertThat(order2003.getStatus()).isEqualTo(OrderStatus.FINISHED);
        assertThat(order2003.getIsActive()).isFalse();
        assertThat(order2003.getFinishedAt()).isNotNull();
        assertThat(order2003.getSnapshotId()).isEqualTo("snap-1");
    }

    @Test
    void ingestDuplicateOrder_shouldUpdateExistingRowIdempotently() {
        OffsetDateTime initialTime = OffsetDateTime.now().minusHours(2);
        OffsetDateTime updateTime = OffsetDateTime.now();

        // 1. Initial order ingestion
        MarketOrderEvent initialEvent = createSampleEvent(3001L, 34L, 50.0, 100L, "snap-init", initialTime);
        listener.handleSingleOrderEvent(initialEvent);

        MarketOrderEntity saved = repository.findById(3001L).orElseThrow();
        OffsetDateTime originalCreatedAt = saved.getCreatedAt();
        assertThat(originalCreatedAt).isNotNull();

        // 2. Updated order ingestion with new price and lower remaining volume
        MarketOrderEvent updateEvent = createSampleEvent(3001L, 34L, 55.0, 60L, "snap-update", updateTime);
        listener.handleSingleOrderEvent(updateEvent);

        // 3. Assert row was updated in place and original createdAt was preserved
        MarketOrderEntity updated = repository.findById(3001L).orElseThrow();
        assertThat(repository.count()).isEqualTo(1L);
        assertThat(updated.getPrice()).isEqualByComparingTo(BigDecimal.valueOf(55.0));
        assertThat(updated.getVolumeRemain()).isEqualTo(60L);
        assertThat(updated.getSnapshotId()).isEqualTo("snap-update");
        assertThat(updated.getCreatedAt()).isEqualTo(originalCreatedAt);
        assertThat(updated.getUpdatedAt()).isAfter(initialTime);
    }

    private MarketOrderEvent createSampleEvent(
            Long orderId,
            Long typeId,
            double price,
            Long volumeRemain,
            String snapshotId,
            OffsetDateTime timestamp
    ) {
        return new MarketOrderEvent(
                orderId,
                typeId,
                10000002L,
                30000142L,
                60003760L,
                BigDecimal.valueOf(price),
                volumeRemain,
                100L,
                1,
                90,
                false,
                "region",
                snapshotId,
                timestamp,
                timestamp
        );
    }
}
