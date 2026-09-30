package com.github.serbentd.eve.poller.service;

import com.evepipeline.esi.api.UniverseApi;
import com.github.serbentd.eve.poller.config.PollerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.util.unit.DataSize;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarketRegionDiscoveryServiceTest {

    private static final Long STATIC_REGION_ID = 10000002L;
    private static final Long THE_FORGE_REGION_ID = 10000002L;
    private static final Long DOMAIN_REGION_ID = 10000043L;
    private static final Long BELOW_MIN_REGION_ID = 9999999L;
    private static final Long WORMHOLE_REGION_ID = 11000001L;
    private static final Long ABYSSAL_REGION_ID = 12000001L;
    private static final Long MIN_MARKET_REGION_ID = 10000000L;
    private static final Long MAX_MARKET_REGION_ID = 11000000L;
    private static final LocalDate COMPATIBILITY_DATE = LocalDate.of(2020, 1, 1);

    @Mock
    private UniverseApi universeApi;

    private PollerProperties.EsiProperties esiProperties;
    private PollerProperties.SchedulingProperties schedulingProperties;
    private PollerProperties.AmqpProperties amqpProperties;

    @BeforeEach
    void setUp() {
        esiProperties = new PollerProperties.EsiProperties(
                "https://esi.evetech.net",
                "EvEBI-Test/1.0",
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                DataSize.ofMegabytes(1),
                20
        );
        schedulingProperties = new PollerProperties.SchedulingProperties(
                List.of(STATIC_REGION_ID),
                Duration.ofHours(1),
                Duration.ofHours(1)
        );
        amqpProperties = new PollerProperties.AmqpProperties(
                "test.market.orders",
                "test.market.orders.raw"
        );
    }

    @Test
    void refreshRegions_whenDiscoveryDisabled_shouldUseStaticConfiguredRegionsAndNeverCallUniverseApi() {
        PollerProperties.DiscoveryProperties discoveryProperties = new PollerProperties.DiscoveryProperties(
                false,
                MIN_MARKET_REGION_ID,
                MAX_MARKET_REGION_ID,
                Duration.ofHours(24),
                COMPATIBILITY_DATE
        );
        PollerProperties properties = new PollerProperties(esiProperties, schedulingProperties, amqpProperties, discoveryProperties);
        MarketRegionDiscoveryService service = new MarketRegionDiscoveryService(universeApi, properties);

        service.refreshRegions();

        assertThat(service.getMarketRegions()).containsExactly(STATIC_REGION_ID);
        verify(universeApi, never()).getUniverseRegions(any(), any(), any(), any(), any());
    }

    @Test
    void refreshRegions_whenDiscoveryEnabled_shouldFilterOutWormholesAndAbyssAndSort() {
        PollerProperties.DiscoveryProperties discoveryProperties = new PollerProperties.DiscoveryProperties(
                true,
                MIN_MARKET_REGION_ID,
                MAX_MARKET_REGION_ID,
                Duration.ofHours(24),
                COMPATIBILITY_DATE
        );
        PollerProperties properties = new PollerProperties(esiProperties, schedulingProperties, amqpProperties, discoveryProperties);
        MarketRegionDiscoveryService service = new MarketRegionDiscoveryService(universeApi, properties);

        List<Long> esiRegions = List.of(
                BELOW_MIN_REGION_ID,
                DOMAIN_REGION_ID,
                THE_FORGE_REGION_ID,
                WORMHOLE_REGION_ID,
                ABYSSAL_REGION_ID
        );

        when(universeApi.getUniverseRegions(eq(COMPATIBILITY_DATE), any(), any(), any(), any()))
                .thenReturn(Flux.fromIterable(esiRegions));

        service.refreshRegions();

        List<Long> marketRegions = service.getMarketRegions();
        assertThat(marketRegions).containsExactly(THE_FORGE_REGION_ID, DOMAIN_REGION_ID);
        verify(universeApi, times(1)).getUniverseRegions(eq(COMPATIBILITY_DATE), any(), any(), any(), any());
    }

    @Test
    void getMarketRegions_whenCalledMultipleTimes_shouldReturnPrecomputedListWithoutRequeryingApi() {
        PollerProperties.DiscoveryProperties discoveryProperties = new PollerProperties.DiscoveryProperties(
                true,
                MIN_MARKET_REGION_ID,
                MAX_MARKET_REGION_ID,
                Duration.ofHours(24),
                COMPATIBILITY_DATE
        );
        PollerProperties properties = new PollerProperties(esiProperties, schedulingProperties, amqpProperties, discoveryProperties);
        MarketRegionDiscoveryService service = new MarketRegionDiscoveryService(universeApi, properties);

        when(universeApi.getUniverseRegions(eq(COMPATIBILITY_DATE), any(), any(), any(), any()))
                .thenReturn(Flux.just(THE_FORGE_REGION_ID));

        service.refreshRegions();

        List<Long> firstCall = service.getMarketRegions();
        List<Long> secondCall = service.getMarketRegions();

        assertThat(firstCall).containsExactly(THE_FORGE_REGION_ID);
        assertThat(secondCall).containsExactly(THE_FORGE_REGION_ID);
        verify(universeApi, times(1)).getUniverseRegions(any(), any(), any(), any(), any());
    }

    @Test
    void refreshRegions_whenUniverseApiThrowsException_shouldCatchAndFallbackToStaticRegions() {
        PollerProperties.DiscoveryProperties discoveryProperties = new PollerProperties.DiscoveryProperties(
                true,
                MIN_MARKET_REGION_ID,
                MAX_MARKET_REGION_ID,
                Duration.ofHours(24),
                COMPATIBILITY_DATE
        );
        PollerProperties properties = new PollerProperties(esiProperties, schedulingProperties, amqpProperties, discoveryProperties);
        MarketRegionDiscoveryService service = new MarketRegionDiscoveryService(universeApi, properties);

        when(universeApi.getUniverseRegions(eq(COMPATIBILITY_DATE), any(), any(), any(), any()))
                .thenReturn(Flux.error(new RuntimeException("ESI Universe outage")));

        assertThatCode(() -> service.refreshRegions()).doesNotThrowAnyException();

        assertThat(service.getMarketRegions()).containsExactly(STATIC_REGION_ID);
    }

    @Test
    void refreshRegions_whenUniverseApiReturnsEmpty_shouldFallbackToStaticRegions() {
        PollerProperties.DiscoveryProperties discoveryProperties = new PollerProperties.DiscoveryProperties(
                true,
                MIN_MARKET_REGION_ID,
                MAX_MARKET_REGION_ID,
                Duration.ofHours(24),
                COMPATIBILITY_DATE
        );
        PollerProperties properties = new PollerProperties(esiProperties, schedulingProperties, amqpProperties, discoveryProperties);
        MarketRegionDiscoveryService service = new MarketRegionDiscoveryService(universeApi, properties);

        when(universeApi.getUniverseRegions(eq(COMPATIBILITY_DATE), any(), any(), any(), any()))
                .thenReturn(Flux.empty());

        service.refreshRegions();

        assertThat(service.getMarketRegions()).containsExactly(STATIC_REGION_ID);
    }

    @Test
    void refreshRegions_whenUniverseApiFailsAfterSuccessfulLoad_shouldRetainPreviouslyLoadedRegions() {
        PollerProperties.DiscoveryProperties discoveryProperties = new PollerProperties.DiscoveryProperties(
                true,
                MIN_MARKET_REGION_ID,
                MAX_MARKET_REGION_ID,
                Duration.ofHours(24),
                COMPATIBILITY_DATE
        );
        PollerProperties properties = new PollerProperties(esiProperties, schedulingProperties, amqpProperties, discoveryProperties);
        MarketRegionDiscoveryService service = new MarketRegionDiscoveryService(universeApi, properties);

        // Run 1: Successful discovery
        when(universeApi.getUniverseRegions(eq(COMPATIBILITY_DATE), any(), any(), any(), any()))
                .thenReturn(Flux.just(THE_FORGE_REGION_ID, DOMAIN_REGION_ID));
        service.refreshRegions();
        assertThat(service.getMarketRegions()).containsExactly(THE_FORGE_REGION_ID, DOMAIN_REGION_ID);

        // Run 2: ESI outage
        when(universeApi.getUniverseRegions(eq(COMPATIBILITY_DATE), any(), any(), any(), any()))
                .thenReturn(Flux.error(new RuntimeException("ESI 503 Service Unavailable")));
        service.refreshRegions();

        // Should retain previously loaded regions rather than reverting to static fallback
        assertThat(service.getMarketRegions()).containsExactly(THE_FORGE_REGION_ID, DOMAIN_REGION_ID);
    }

    @Test
    void getMarketRegions_whenUninitializedAndEnabled_shouldTriggerRefreshAutomatically() {
        PollerProperties.DiscoveryProperties discoveryProperties = new PollerProperties.DiscoveryProperties(
                true,
                MIN_MARKET_REGION_ID,
                MAX_MARKET_REGION_ID,
                Duration.ofHours(24),
                COMPATIBILITY_DATE
        );
        PollerProperties properties = new PollerProperties(esiProperties, schedulingProperties, amqpProperties, discoveryProperties);
        MarketRegionDiscoveryService service = new MarketRegionDiscoveryService(universeApi, properties);

        when(universeApi.getUniverseRegions(eq(COMPATIBILITY_DATE), any(), any(), any(), any()))
                .thenReturn(Flux.just(THE_FORGE_REGION_ID));

        List<Long> regions = service.getMarketRegions();
        assertThat(regions).containsExactly(THE_FORGE_REGION_ID);
        verify(universeApi, times(1)).getUniverseRegions(any(), any(), any(), any(), any());
    }
}
