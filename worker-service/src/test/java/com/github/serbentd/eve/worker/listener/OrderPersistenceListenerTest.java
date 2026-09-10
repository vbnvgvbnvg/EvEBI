package com.github.serbentd.eve.worker.listener;

import com.github.serbentd.eve.worker.event.MarketOrderEvent;
import com.github.serbentd.eve.worker.event.RegionSnapshotCompletedEvent;
import com.github.serbentd.eve.worker.service.OrderPersistenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderPersistenceListenerTest {

    @Mock
    private OrderPersistenceService orderPersistenceService;

    private OrderPersistenceListener listener;

    @BeforeEach
    void setUp() {
        listener = new OrderPersistenceListener(orderPersistenceService);
    }

    @Test
    void handleOrderEvents_shouldDelegateListToService() {
        OffsetDateTime now = OffsetDateTime.now();
        List<MarketOrderEvent> events = List.of(
                createSampleEvent(1001L, now),
                createSampleEvent(1002L, now)
        );

        listener.handleOrderEvents(events);

        verify(orderPersistenceService).persistOrders(events);
    }

    @Test
    void handleSingleOrderEvent_shouldWrapInListAndDelegate() {
        OffsetDateTime now = OffsetDateTime.now();
        MarketOrderEvent event = createSampleEvent(1001L, now);

        listener.handleSingleOrderEvent(event);

        verify(orderPersistenceService).persistOrders(List.of(event));
    }

    @Test
    void handleSnapshotCompleted_shouldDelegateReconciliation() {
        OffsetDateTime now = OffsetDateTime.now();
        RegionSnapshotCompletedEvent event = new RegionSnapshotCompletedEvent(
                10000002L,
                "snap-456",
                1200,
                12,
                now
        );

        when(orderPersistenceService.reconcileSnapshot(event)).thenReturn(25);

        listener.handleSnapshotCompleted(event);

        verify(orderPersistenceService).reconcileSnapshot(event);
    }

    private MarketOrderEvent createSampleEvent(Long orderId, OffsetDateTime timestamp) {
        return new MarketOrderEvent(
                orderId,
                34L,
                10000002L,
                30000142L,
                60003760L,
                BigDecimal.valueOf(100.50),
                50L,
                100L,
                1,
                90,
                false,
                "region",
                "snap-456",
                timestamp,
                timestamp
        );
    }
}
