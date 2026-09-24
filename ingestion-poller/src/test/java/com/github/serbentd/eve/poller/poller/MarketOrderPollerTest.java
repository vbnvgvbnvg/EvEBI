package com.github.serbentd.eve.poller.poller;

import com.evepipeline.esi.api.MarketApi;
import com.evepipeline.esi.model.MarketsRegionIdOrdersGetInner;
import com.github.serbentd.eve.poller.config.PollerProperties;
import com.github.serbentd.eve.poller.event.MarketOrderEvent;
import com.github.serbentd.eve.poller.event.RegionSnapshotCompletedEvent;
import com.github.serbentd.eve.poller.service.OrderEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.util.unit.DataSize;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarketOrderPollerTest {

    private static final Long THE_FORGE_REGION_ID = 10000002L;
    private static final Long DOMAIN_REGION_ID = 10000043L;
    private static final Long JITA_SYSTEM_ID = 30000142L;
    private static final Long JITA_4_4_STATION_ID = 60003760L;

    @Mock
    private MarketApi marketApi;

    @Mock
    private OrderEventPublisher publisher;

    private PollerProperties properties;
    private MarketOrderPoller poller;

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
                "test.market.orders",
                "test.market.orders.raw"
        );
        properties = new PollerProperties(esi, scheduling, amqp);
        poller = new MarketOrderPoller(marketApi, publisher, properties);
    }

    @Test
    void pollAllRegions_singlePageSuccess_shouldPublishOrdersAndSnapshotCompletion() {
        MarketsRegionIdOrdersGetInner order1 = createSampleOrder(1001L, 34L, 15.5, 50L);
        MarketsRegionIdOrdersGetInner order2 = createSampleOrder(1002L, 34L, 16.0, 100L);

        ResponseEntity<List<MarketsRegionIdOrdersGetInner>> page1Response = ResponseEntity.ok()
                .header("X-Pages", "1")
                .body(List.of(order1, order2));

        when(marketApi.getMarketsRegionIdOrdersWithHttpInfo(
                eq("all"), eq(THE_FORGE_REGION_ID), isNull(), eq(1), isNull(), isNull(), isNull(), isNull(), isNull()
        )).thenReturn(Mono.just(page1Response));

        poller.pollAllRegions();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MarketOrderEvent>> orderBatchCaptor = ArgumentCaptor.forClass(List.class);
        verify(publisher).publishOrderBatch(orderBatchCaptor.capture());

        List<MarketOrderEvent> capturedOrders = orderBatchCaptor.getValue();
        assertThat(capturedOrders).hasSize(2);

        MarketOrderEvent firstEvent = capturedOrders.getFirst();
        assertThat(firstEvent.orderId()).isEqualTo(1001L);
        assertThat(firstEvent.typeId()).isEqualTo(34L);
        assertThat(firstEvent.regionId()).isEqualTo(THE_FORGE_REGION_ID);
        assertThat(firstEvent.systemId()).isEqualTo(JITA_SYSTEM_ID);
        assertThat(firstEvent.locationId()).isEqualTo(JITA_4_4_STATION_ID);
        assertThat(firstEvent.price()).isEqualByComparingTo(BigDecimal.valueOf(15.5));
        assertThat(firstEvent.volumeRemain()).isEqualTo(50L);
        assertThat(firstEvent.volumeTotal()).isEqualTo(100L);
        assertThat(firstEvent.minVolume()).isEqualTo(1);
        assertThat(firstEvent.duration()).isEqualTo(90);
        assertThat(firstEvent.isBuyOrder()).isFalse();
        assertThat(firstEvent.orderRange()).isEqualTo("region");
        assertThat(firstEvent.snapshotId()).isNotBlank();
        assertThat(firstEvent.issued()).isNotNull();
        assertThat(firstEvent.polledAt()).isNotNull();

        ArgumentCaptor<RegionSnapshotCompletedEvent> snapshotCaptor = ArgumentCaptor.forClass(RegionSnapshotCompletedEvent.class);
        verify(publisher).publishSnapshotCompleted(snapshotCaptor.capture());

        RegionSnapshotCompletedEvent capturedSnapshot = snapshotCaptor.getValue();
        assertThat(capturedSnapshot.regionId()).isEqualTo(THE_FORGE_REGION_ID);
        assertThat(capturedSnapshot.snapshotId()).isEqualTo(firstEvent.snapshotId());
        assertThat(capturedSnapshot.totalOrders()).isEqualTo(2);
        assertThat(capturedSnapshot.totalPages()).isEqualTo(1);
        assertThat(capturedSnapshot.completedAt()).isNotNull();
    }

    @Test
    void pollAllRegions_multiPageSuccess_shouldTraverseAllPagesAndAccumulateTotals() {
        MarketsRegionIdOrdersGetInner order1 = createSampleOrder(1001L, 34L, 15.5, 50L);
        MarketsRegionIdOrdersGetInner order2 = createSampleOrder(1002L, 34L, 16.0, 100L);
        MarketsRegionIdOrdersGetInner order3 = createSampleOrder(1003L, 35L, 25.0, 30L);

        ResponseEntity<List<MarketsRegionIdOrdersGetInner>> page1Response = ResponseEntity.ok()
                .header("X-Pages", "2")
                .body(List.of(order1, order2));

        ResponseEntity<List<MarketsRegionIdOrdersGetInner>> page2Response = ResponseEntity.ok()
                .header("X-Pages", "2")
                .body(List.of(order3));

        when(marketApi.getMarketsRegionIdOrdersWithHttpInfo(
                eq("all"), eq(THE_FORGE_REGION_ID), isNull(), eq(1), isNull(), isNull(), isNull(), isNull(), isNull()
        )).thenReturn(Mono.just(page1Response));

        when(marketApi.getMarketsRegionIdOrdersWithHttpInfo(
                eq("all"), eq(THE_FORGE_REGION_ID), isNull(), eq(2), isNull(), isNull(), isNull(), isNull(), isNull()
        )).thenReturn(Mono.just(page2Response));

        poller.pollAllRegions();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MarketOrderEvent>> orderBatchCaptor = ArgumentCaptor.forClass(List.class);
        verify(publisher, times(2)).publishOrderBatch(orderBatchCaptor.capture());

        List<List<MarketOrderEvent>> capturedBatches = orderBatchCaptor.getAllValues();
        assertThat(capturedBatches.get(0)).hasSize(2);
        assertThat(capturedBatches.get(1)).hasSize(1);

        ArgumentCaptor<RegionSnapshotCompletedEvent> snapshotCaptor = ArgumentCaptor.forClass(RegionSnapshotCompletedEvent.class);
        verify(publisher).publishSnapshotCompleted(snapshotCaptor.capture());

        RegionSnapshotCompletedEvent capturedSnapshot = snapshotCaptor.getValue();
        assertThat(capturedSnapshot.regionId()).isEqualTo(THE_FORGE_REGION_ID);
        assertThat(capturedSnapshot.totalOrders()).isEqualTo(3);
        assertThat(capturedSnapshot.totalPages()).isEqualTo(2);
    }

    @Test
    void pollAllRegions_whenXPagesHeaderMissingOrInvalid_shouldDefaultToOnePage() {
        MarketsRegionIdOrdersGetInner order = createSampleOrder(1001L, 34L, 15.5, 50L);

        ResponseEntity<List<MarketsRegionIdOrdersGetInner>> page1Response = ResponseEntity.ok()
                .header("X-Pages", "not-a-number")
                .body(List.of(order));

        when(marketApi.getMarketsRegionIdOrdersWithHttpInfo(
                eq("all"), eq(THE_FORGE_REGION_ID), isNull(), eq(1), isNull(), isNull(), isNull(), isNull(), isNull()
        )).thenReturn(Mono.just(page1Response));

        poller.pollAllRegions();

        verify(publisher, times(1)).publishOrderBatch(any());

        ArgumentCaptor<RegionSnapshotCompletedEvent> snapshotCaptor = ArgumentCaptor.forClass(RegionSnapshotCompletedEvent.class);
        verify(publisher).publishSnapshotCompleted(snapshotCaptor.capture());

        RegionSnapshotCompletedEvent capturedSnapshot = snapshotCaptor.getValue();
        assertThat(capturedSnapshot.totalPages()).isEqualTo(1);
        assertThat(capturedSnapshot.totalOrders()).isEqualTo(1);
    }

    @Test
    void pollAllRegions_whenApiThrowsException_shouldCatchAndNotCrashScheduler() {
        when(marketApi.getMarketsRegionIdOrdersWithHttpInfo(
                eq("all"), eq(THE_FORGE_REGION_ID), isNull(), eq(1), isNull(), isNull(), isNull(), isNull(), isNull()
        )).thenReturn(Mono.error(new RuntimeException("ESI network failure")));

        assertThatCode(() -> poller.pollAllRegions()).doesNotThrowAnyException();

        verify(publisher, never()).publishOrderBatch(any());
        verify(publisher, never()).publishSnapshotCompleted(any());
    }

    @Test
    void pollAllRegions_multipleConfiguredRegions_shouldProcessEachIndependently() {
        PollerProperties multiRegionProperties = new PollerProperties(
                properties.esi(),
                new PollerProperties.SchedulingProperties(
                        List.of(THE_FORGE_REGION_ID, DOMAIN_REGION_ID),
                        Duration.ofHours(1),
                        Duration.ofHours(1)
                ),
                properties.amqp()
        );
        MarketOrderPoller multiRegionPoller = new MarketOrderPoller(marketApi, publisher, multiRegionProperties);

        MarketsRegionIdOrdersGetInner forgeOrder = createSampleOrder(1001L, 34L, 15.5, 50L);
        MarketsRegionIdOrdersGetInner domainOrder = createSampleOrder(2001L, 34L, 14.0, 80L);

        when(marketApi.getMarketsRegionIdOrdersWithHttpInfo(
                eq("all"), eq(THE_FORGE_REGION_ID), isNull(), eq(1), isNull(), isNull(), isNull(), isNull(), isNull()
        )).thenReturn(Mono.just(ResponseEntity.ok().header("X-Pages", "1").body(List.of(forgeOrder))));

        when(marketApi.getMarketsRegionIdOrdersWithHttpInfo(
                eq("all"), eq(DOMAIN_REGION_ID), isNull(), eq(1), isNull(), isNull(), isNull(), isNull(), isNull()
        )).thenReturn(Mono.just(ResponseEntity.ok().header("X-Pages", "1").body(List.of(domainOrder))));

        multiRegionPoller.pollAllRegions();

        verify(publisher, times(2)).publishOrderBatch(any());

        ArgumentCaptor<RegionSnapshotCompletedEvent> snapshotCaptor = ArgumentCaptor.forClass(RegionSnapshotCompletedEvent.class);
        verify(publisher, times(2)).publishSnapshotCompleted(snapshotCaptor.capture());

        List<RegionSnapshotCompletedEvent> capturedSnapshots = snapshotCaptor.getAllValues();
        assertThat(capturedSnapshots).extracting(RegionSnapshotCompletedEvent::regionId)
                .containsExactly(THE_FORGE_REGION_ID, DOMAIN_REGION_ID);
    }

    @Test
    void pollRegion_whenPage1ResponseIsNull_shouldLogWarningAndReturnEarly() {
        when(marketApi.getMarketsRegionIdOrdersWithHttpInfo(
                eq("all"), eq(THE_FORGE_REGION_ID), isNull(), eq(1), isNull(), isNull(), isNull(), isNull(), isNull()
        )).thenReturn(Mono.empty());

        poller.pollRegion(THE_FORGE_REGION_ID);

        verify(publisher, never()).publishOrderBatch(any());
        verify(publisher, never()).publishSnapshotCompleted(any());
    }

    @Test
    void pollRegion_whenSubsequentPageResponseOrBodyIsNull_shouldSkipAndContinue() {
        MarketsRegionIdOrdersGetInner order1 = createSampleOrder(1001L, 34L, 15.5, 50L);
        MarketsRegionIdOrdersGetInner order4 = createSampleOrder(1004L, 35L, 25.0, 30L);

        // Page 1 has X-Pages: 4
        ResponseEntity<List<MarketsRegionIdOrdersGetInner>> page1Response = ResponseEntity.ok()
                .header("X-Pages", "4")
                .body(List.of(order1));

        // Page 2 returns null response (covers pageResponse == null branch)
        // Page 3 returns response with null body (covers pageResponse.getBody() == null branch)
        ResponseEntity<List<MarketsRegionIdOrdersGetInner>> page3Response = ResponseEntity.ok().body(null);

        // Page 4 has order 4
        ResponseEntity<List<MarketsRegionIdOrdersGetInner>> page4Response = ResponseEntity.ok()
                .header("X-Pages", "4")
                .body(List.of(order4));

        when(marketApi.getMarketsRegionIdOrdersWithHttpInfo(
                eq("all"), eq(THE_FORGE_REGION_ID), isNull(), eq(1), isNull(), isNull(), isNull(), isNull(), isNull()
        )).thenReturn(Mono.just(page1Response));

        when(marketApi.getMarketsRegionIdOrdersWithHttpInfo(
                eq("all"), eq(THE_FORGE_REGION_ID), isNull(), eq(2), isNull(), isNull(), isNull(), isNull(), isNull()
        )).thenReturn(Mono.empty());

        when(marketApi.getMarketsRegionIdOrdersWithHttpInfo(
                eq("all"), eq(THE_FORGE_REGION_ID), isNull(), eq(3), isNull(), isNull(), isNull(), isNull(), isNull()
        )).thenReturn(Mono.just(page3Response));

        when(marketApi.getMarketsRegionIdOrdersWithHttpInfo(
                eq("all"), eq(THE_FORGE_REGION_ID), isNull(), eq(4), isNull(), isNull(), isNull(), isNull(), isNull()
        )).thenReturn(Mono.just(page4Response));

        poller.pollRegion(THE_FORGE_REGION_ID);

        verify(publisher, times(2)).publishOrderBatch(any());

        ArgumentCaptor<RegionSnapshotCompletedEvent> snapshotCaptor = ArgumentCaptor.forClass(RegionSnapshotCompletedEvent.class);
        verify(publisher).publishSnapshotCompleted(snapshotCaptor.capture());

        RegionSnapshotCompletedEvent snapshot = snapshotCaptor.getValue();
        assertThat(snapshot.totalOrders()).isEqualTo(2);
        assertThat(snapshot.totalPages()).isEqualTo(4);
    }

    @Test
    void pollRegion_whenPage1BodyIsNull_shouldHandleGracefullyWithoutPublishingBatch() {
        ResponseEntity<List<MarketsRegionIdOrdersGetInner>> page1Response = ResponseEntity.ok()
                .header("X-Pages", "1")
                .body(null);

        when(marketApi.getMarketsRegionIdOrdersWithHttpInfo(
                eq("all"), eq(THE_FORGE_REGION_ID), isNull(), eq(1), isNull(), isNull(), isNull(), isNull(), isNull()
        )).thenReturn(Mono.just(page1Response));

        poller.pollRegion(THE_FORGE_REGION_ID);

        verify(publisher, never()).publishOrderBatch(any());

        ArgumentCaptor<RegionSnapshotCompletedEvent> snapshotCaptor = ArgumentCaptor.forClass(RegionSnapshotCompletedEvent.class);
        verify(publisher).publishSnapshotCompleted(snapshotCaptor.capture());

        RegionSnapshotCompletedEvent snapshot = snapshotCaptor.getValue();
        assertThat(snapshot.totalOrders()).isEqualTo(0);
        assertThat(snapshot.totalPages()).isEqualTo(1);
    }

    @Test
    void pollRegion_whenXPagesHeaderCompletelyAbsent_shouldDefaultToOnePage() {
        MarketsRegionIdOrdersGetInner order = createSampleOrder(1001L, 34L, 15.5, 50L);

        ResponseEntity<List<MarketsRegionIdOrdersGetInner>> page1Response = ResponseEntity.ok()
                .body(List.of(order));

        when(marketApi.getMarketsRegionIdOrdersWithHttpInfo(
                eq("all"), eq(THE_FORGE_REGION_ID), isNull(), eq(1), isNull(), isNull(), isNull(), isNull(), isNull()
        )).thenReturn(Mono.just(page1Response));

        poller.pollRegion(THE_FORGE_REGION_ID);

        verify(publisher, times(1)).publishOrderBatch(any());

        ArgumentCaptor<RegionSnapshotCompletedEvent> snapshotCaptor = ArgumentCaptor.forClass(RegionSnapshotCompletedEvent.class);
        verify(publisher).publishSnapshotCompleted(snapshotCaptor.capture());

        RegionSnapshotCompletedEvent snapshot = snapshotCaptor.getValue();
        assertThat(snapshot.totalPages()).isEqualTo(1);
        assertThat(snapshot.totalOrders()).isEqualTo(1);
    }

    @Test
    void pollRegion_whenOrderPageIsEmpty_shouldReturnZeroAndNotPublishBatch() {
        ResponseEntity<List<MarketsRegionIdOrdersGetInner>> page1Response = ResponseEntity.ok()
                .header("X-Pages", "1")
                .body(List.of());

        when(marketApi.getMarketsRegionIdOrdersWithHttpInfo(
                eq("all"), eq(THE_FORGE_REGION_ID), isNull(), eq(1), isNull(), isNull(), isNull(), isNull(), isNull()
        )).thenReturn(Mono.just(page1Response));

        poller.pollRegion(THE_FORGE_REGION_ID);

        verify(publisher, never()).publishOrderBatch(any());

        ArgumentCaptor<RegionSnapshotCompletedEvent> snapshotCaptor = ArgumentCaptor.forClass(RegionSnapshotCompletedEvent.class);
        verify(publisher).publishSnapshotCompleted(snapshotCaptor.capture());

        RegionSnapshotCompletedEvent snapshot = snapshotCaptor.getValue();
        assertThat(snapshot.totalOrders()).isEqualTo(0);
        assertThat(snapshot.totalPages()).isEqualTo(1);
    }

    private MarketsRegionIdOrdersGetInner createSampleOrder(Long orderId, Long typeId, Double price, Long volumeRemain) {
        return new MarketsRegionIdOrdersGetInner()
                .orderId(orderId)
                .typeId(typeId)
                .systemId(JITA_SYSTEM_ID)
                .locationId(JITA_4_4_STATION_ID)
                .price(price)
                .volumeRemain(volumeRemain)
                .volumeTotal(100L)
                .minVolume(1L)
                .duration(90L)
                .isBuyOrder(false)
                .range(MarketsRegionIdOrdersGetInner.RangeEnum.REGION)
                .issued(OffsetDateTime.now());
    }
}
