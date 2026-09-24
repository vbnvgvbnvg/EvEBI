package com.github.serbentd.eve.poller.poller;

import com.evepipeline.esi.api.MarketApi;
import com.evepipeline.esi.model.MarketsRegionIdOrdersGetInner;
import com.github.serbentd.eve.poller.config.PollerProperties;
import com.github.serbentd.eve.poller.event.MarketOrderEvent;
import com.github.serbentd.eve.poller.event.RegionSnapshotCompletedEvent;
import com.github.serbentd.eve.poller.service.OrderEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Scheduled service responsible for polling market order books from CCP's ESI API.
 * <p>
 * Handles multi-page pagination using the {@code X-Pages} HTTP header, maps ESI response
 * models into immutable {@link MarketOrderEvent} batches, streams them to RabbitMQ,
 * and emits a {@link RegionSnapshotCompletedEvent} upon snapshot completion.
 */
@Slf4j
@Component
public class MarketOrderPoller {

    private static final String ORDER_TYPE_ALL = "all";
    private static final String HEADER_X_PAGES = "X-Pages";

    private final MarketApi marketApi;
    private final OrderEventPublisher publisher;
    private final PollerProperties properties;

    public MarketOrderPoller(MarketApi marketApi, OrderEventPublisher publisher, PollerProperties properties) {
        this.marketApi = marketApi;
        this.publisher = publisher;
        this.properties = properties;
    }

    /**
     * Scheduled entry point to poll all configured market regions.
     */
    @Scheduled(
            initialDelayString = "${eve.poller.scheduling.initial-delay}",
            fixedRateString = "${eve.poller.scheduling.fixed-rate}"
    )
    public void pollAllRegions() {
        List<Long> regions = properties.scheduling().regions();
        log.info("Starting scheduled market order polling across {} region(s)", regions.size());

        for (Long regionId : regions) {
            try {
                pollRegion(regionId);
            } catch (Exception e) {
                log.error("Failed to poll market orders for region {}", regionId, e);
            }
        }
    }

    /**
     * Polls all market order pages for a specific region snapshot.
     *
     * @param regionId solar region identifier
     */
    public void pollRegion(Long regionId) {
        String snapshotId = UUID.randomUUID().toString();
        OffsetDateTime polledAt = OffsetDateTime.now(ZoneOffset.UTC);

        log.info("Polling region {} (snapshotId: {})", regionId, snapshotId);

        ResponseEntity<List<MarketsRegionIdOrdersGetInner>> page1Response = fetchPage(regionId, 1);
        if (page1Response == null) {
            log.warn("Received null response for region {} page 1, skipping snapshot {}", regionId, snapshotId);
            return;
        }

        int totalPages = extractTotalPages(page1Response, regionId);
        int totalOrders = processOrders(page1Response.getBody(), regionId, snapshotId, polledAt);

        for (int page = 2; page <= totalPages; page++) {
            ResponseEntity<List<MarketsRegionIdOrdersGetInner>> pageResponse = fetchPage(regionId, page);
            if (pageResponse != null && pageResponse.getBody() != null) {
                totalOrders += processOrders(pageResponse.getBody(), regionId, snapshotId, polledAt);
            }
        }

        RegionSnapshotCompletedEvent completionEvent = new RegionSnapshotCompletedEvent(
                regionId,
                snapshotId,
                totalOrders,
                totalPages,
                OffsetDateTime.now(ZoneOffset.UTC)
        );
        publisher.publishSnapshotCompleted(completionEvent);

        log.info("Finished polling region {}: {} total orders across {} page(s) (snapshotId: {})",
                regionId, totalOrders, totalPages, snapshotId);
    }

    private ResponseEntity<List<MarketsRegionIdOrdersGetInner>> fetchPage(Long regionId, int page) {
        return marketApi.getMarketsRegionIdOrdersWithHttpInfo(
                ORDER_TYPE_ALL, regionId, null, page, null, null, null, null, null
        ).block();
    }

    private int extractTotalPages(ResponseEntity<List<MarketsRegionIdOrdersGetInner>> response, Long regionId) {
        String pagesHeader = response.getHeaders().getFirst(HEADER_X_PAGES);
        if (pagesHeader != null) {
            try {
                return Integer.parseInt(pagesHeader.trim());
            } catch (NumberFormatException e) {
                log.warn("Invalid {} header '{}' for region {}, defaulting to 1 page", HEADER_X_PAGES, pagesHeader, regionId);
            }
        }
        return 1;
    }

    private int processOrders(List<MarketsRegionIdOrdersGetInner> items, Long regionId, String snapshotId, OffsetDateTime polledAt) {
        if (items == null || items.isEmpty()) {
            return 0;
        }

        List<MarketOrderEvent> events = items.stream()
                .map(item -> new MarketOrderEvent(
                        item.getOrderId(),
                        item.getTypeId(),
                        regionId,
                        item.getSystemId(),
                        item.getLocationId(),
                        BigDecimal.valueOf(item.getPrice()),
                        item.getVolumeRemain(),
                        item.getVolumeTotal(),
                        Math.toIntExact(item.getMinVolume()),
                        Math.toIntExact(item.getDuration()),
                        Boolean.TRUE.equals(item.getIsBuyOrder()),
                        item.getRange().getValue(),
                        snapshotId,
                        item.getIssued(),
                        polledAt
                ))
                .toList();

        publisher.publishOrderBatch(events);
        return events.size();
    }
}
