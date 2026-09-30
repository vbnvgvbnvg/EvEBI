package com.github.serbentd.eve.poller.service;

import com.evepipeline.esi.api.UniverseApi;
import com.github.serbentd.eve.poller.config.PollerProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * Service responsible for dynamically discovering and maintaining the active New Eden market regions.
 * <p>
 * Periodically fetches all universe regions from CCP's ESI Universe API, filters out non-market space
 * (such as Anoikis wormholes and Abyssal deadspace), and keeps an in-memory cache of valid market regions
 * ready for the market order scraper.
 */
@Slf4j
@Service
public class MarketRegionDiscoveryService {

    private final UniverseApi universeApi;
    private final PollerProperties properties;
    private volatile List<Long> marketRegions = Collections.emptyList();

    public MarketRegionDiscoveryService(UniverseApi universeApi, PollerProperties properties) {
        this.universeApi = universeApi;
        this.properties = properties;
    }

    /**
     * Refreshes the discovered market regions on application startup and at scheduled intervals.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(fixedRateString = "${eve.poller.discovery.refresh-rate}")
    public void refreshRegions() {
        if (!properties.discovery().enabled()) {
            log.info("Dynamic universe region discovery is disabled; using {} static configured region(s)",
                    properties.scheduling().regions().size());
            this.marketRegions = List.copyOf(properties.scheduling().regions());
            return;
        }

        log.info("Refreshing market regions via ESI Universe API");
        try {
            List<Long> allRegions = universeApi.getUniverseRegions(
                    properties.discovery().compatibilityDate(),
                    null,
                    null,
                    null,
                    null
            ).collectList().block();

            if (allRegions != null && !allRegions.isEmpty()) {
                Long minId = properties.discovery().minRegionId();
                Long maxId = properties.discovery().maxRegionId();

                List<Long> filtered = allRegions.stream()
                        .filter(id -> id != null && id >= minId && id < maxId)
                        .sorted()
                        .toList();

                if (!filtered.isEmpty()) {
                    this.marketRegions = filtered;
                    log.info("Successfully discovered and loaded {} active market regions (filtered from {} total universe regions)",
                            filtered.size(), allRegions.size());
                    return;
                }

                log.warn("ESI Universe API returned zero regions within valid market range [{}, {}). Retaining previous or fallback regions",
                        minId, maxId);
            } else {
                log.warn("ESI Universe API returned empty region list. Retaining previous or fallback regions");
            }
        } catch (Exception e) {
            log.error("Failed to refresh market regions from ESI Universe API: {}", e.getMessage(), e);
        }

        // If no regions were discovered and cache is still empty, apply static fallback
        if (this.marketRegions.isEmpty()) {
            log.warn("Applying static fallback of {} configured region(s)", properties.scheduling().regions().size());
            this.marketRegions = List.copyOf(properties.scheduling().regions());
        }
    }

    /**
     * Returns the currently active list of market regions to scrape.
     *
     * @return immutable list of regional IDs
     */
    public List<Long> getMarketRegions() {
        if (!properties.discovery().enabled()) {
            return properties.scheduling().regions();
        }

        if (this.marketRegions.isEmpty()) {
            refreshRegions();
        }
        return this.marketRegions;
    }
}
