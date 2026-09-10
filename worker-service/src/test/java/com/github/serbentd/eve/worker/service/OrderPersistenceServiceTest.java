package com.github.serbentd.eve.worker.service;

import com.github.serbentd.eve.worker.config.MarketProperties;
import com.github.serbentd.eve.worker.domain.MarketOrderEntity;
import com.github.serbentd.eve.worker.domain.OrderStatus;
import com.github.serbentd.eve.worker.event.MarketOrderEvent;
import com.github.serbentd.eve.worker.event.RegionSnapshotCompletedEvent;
import com.github.serbentd.eve.worker.repository.MarketOrderRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderPersistenceServiceTest {

    @Mock
    private MarketOrderRepository marketOrderRepository;

    @Mock
    private EntityManager entityManager;

    private MarketProperties properties;
    private OrderPersistenceService orderPersistenceService;

    @BeforeEach
    void setUp() {
        // Configure chunk size of 2 for testing partitioning boundaries
        properties = new MarketProperties("eve.market.orders", "eve.market.orders.worker", "eve.market.orders.#", 2);
        orderPersistenceService = new OrderPersistenceService(marketOrderRepository, entityManager, properties);
    }

    @Test
    void persistOrders_whenEventsNullOrEmpty_shouldReturnImmediately() {
        orderPersistenceService.persistOrders(null);
        orderPersistenceService.persistOrders(List.of());

        verifyNoInteractions(marketOrderRepository);
        verifyNoInteractions(entityManager);
    }

    @Test
    void persistOrders_whenOrdersAreNew_shouldInsertWithActiveStatusAndCreatedAt() {
        OffsetDateTime now = OffsetDateTime.now();
        MarketOrderEvent event1 = createSampleEvent(1001L, 34L, 15.0, 50L, now);
        MarketOrderEvent event2 = createSampleEvent(1002L, 34L, 16.5, 100L, now);

        when(marketOrderRepository.findAllById(anySet())).thenReturn(List.of());

        orderPersistenceService.persistOrders(List.of(event1, event2));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MarketOrderEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(marketOrderRepository).saveAll(captor.capture());

        List<MarketOrderEntity> savedEntities = captor.getValue();
        assertThat(savedEntities).hasSize(2);

        MarketOrderEntity first = savedEntities.stream()
                .filter(e -> e.getOrderId().equals(1001L))
                .findFirst()
                .orElseThrow();

        assertThat(first.getPrice()).isEqualByComparingTo(BigDecimal.valueOf(15.0));
        assertThat(first.getStatus()).isEqualTo(OrderStatus.ACTIVE);
        assertThat(first.getIsActive()).isTrue();
        assertThat(first.getCreatedAt()).isNotNull();
        assertThat(first.getUpdatedAt()).isNotNull();
        assertThat(first.getSnapshotId()).isEqualTo("snap-1");

        verify(entityManager).flush();
        verify(entityManager).clear();
    }

    @Test
    void persistOrders_whenOrderExists_shouldUpdateAttributesAndPreserveCreatedAt() {
        OffsetDateTime initialCreated = OffsetDateTime.now().minusDays(5);
        MarketOrderEntity existing = MarketOrderEntity.builder()
                .orderId(1001L)
                .typeId(34L)
                .regionId(10000002L)
                .systemId(30000142L)
                .locationId(60003760L)
                .price(BigDecimal.valueOf(10.0))
                .volumeRemain(100L)
                .volumeTotal(100L)
                .minVolume(1)
                .duration(90)
                .isBuyOrder(false)
                .orderRange("region")
                .status(OrderStatus.ACTIVE)
                .isActive(true)
                .snapshotId("snap-old")
                .issued(initialCreated)
                .polledAt(initialCreated)
                .createdAt(initialCreated)
                .updatedAt(initialCreated)
                .build();

        when(marketOrderRepository.findAllById(anySet())).thenReturn(List.of(existing));

        OffsetDateTime newPolledAt = OffsetDateTime.now();
        MarketOrderEvent updatedEvent = createSampleEvent(1001L, 34L, 12.5, 80L, newPolledAt);

        orderPersistenceService.persistOrders(List.of(updatedEvent));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MarketOrderEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(marketOrderRepository).saveAll(captor.capture());

        List<MarketOrderEntity> savedEntities = captor.getValue();
        assertThat(savedEntities).hasSize(1);
        MarketOrderEntity saved = savedEntities.getFirst();

        assertThat(saved.getOrderId()).isEqualTo(1001L);
        assertThat(saved.getPrice()).isEqualByComparingTo(BigDecimal.valueOf(12.5));
        assertThat(saved.getVolumeRemain()).isEqualTo(80L);
        assertThat(saved.getSnapshotId()).isEqualTo("snap-1");
        assertThat(saved.getCreatedAt()).isEqualTo(initialCreated);
        assertThat(saved.getUpdatedAt()).isAfter(initialCreated);

        verify(entityManager).flush();
        verify(entityManager).clear();
    }

    @Test
    void persistOrders_whenEventsExceedBatchSize_shouldPartitionAndFlushPerChunk() {
        OffsetDateTime now = OffsetDateTime.now();
        List<MarketOrderEvent> fiveEvents = List.of(
                createSampleEvent(1L, 34L, 10.0, 10L, now),
                createSampleEvent(2L, 34L, 11.0, 20L, now),
                createSampleEvent(3L, 34L, 12.0, 30L, now),
                createSampleEvent(4L, 34L, 13.0, 40L, now),
                createSampleEvent(5L, 34L, 14.0, 50L, now)
        );

        when(marketOrderRepository.findAllById(anySet())).thenReturn(List.of());

        orderPersistenceService.persistOrders(fiveEvents);

        // With batchSize = 2 and 5 elements, there should be 3 chunks: [2, 2, 1]
        verify(marketOrderRepository, times(3)).saveAll(any());
        verify(entityManager, times(3)).flush();
        verify(entityManager, times(3)).clear();
    }

    @Test
    void reconcileSnapshot_shouldExecuteBulkUpdateAndReturnModifiedCount() {
        Query query = mock(Query.class);
        when(entityManager.createQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.executeUpdate()).thenReturn(15);

        OffsetDateTime completedAt = OffsetDateTime.now();
        RegionSnapshotCompletedEvent event = new RegionSnapshotCompletedEvent(
                10000002L,
                "snap-123",
                450,
                5,
                completedAt
        );

        int reconciledCount = orderPersistenceService.reconcileSnapshot(event);

        assertThat(reconciledCount).isEqualTo(15);
        verify(query).setParameter("finishedStatus", OrderStatus.FINISHED);
        verify(query).setParameter("finishedAt", completedAt);
        verify(query).setParameter("regionId", 10000002L);
        verify(query).setParameter("snapshotId", "snap-123");
        verify(query).executeUpdate();
    }

    private MarketOrderEvent createSampleEvent(Long orderId, Long typeId, Double price, Long volumeRemain, OffsetDateTime polledAt) {
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
                "snap-1",
                polledAt,
                polledAt
        );
    }
}
