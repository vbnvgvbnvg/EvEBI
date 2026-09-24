package com.github.serbentd.eve.poller.config;

import com.evepipeline.esi.ApiClient;
import com.evepipeline.esi.api.MarketApi;
import com.github.serbentd.eve.poller.client.EsiErrorBudgetFilter;
import io.netty.channel.ChannelOption;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/**
 * Spring WebFlux and ESI client configuration.
 * <p>
 * Configures the reactive {@link WebClient} with Netty timeouts, memory buffer sizing,
 * and identifying {@code User-Agent} headers, and exposes OpenAPI {@link ApiClient}
 * and {@link MarketApi} beans.
 */
@Configuration
public class WebClientConfig {

    private final PollerProperties properties;

    public WebClientConfig(PollerProperties properties) {
        this.properties = properties;
    }

    /**
     * Configured reactive WebClient for non-blocking HTTP requests to CCP ESI.
     */
    @Bean
    public WebClient esiWebClient(EsiErrorBudgetFilter errorBudgetFilter) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) properties.esi().connectTimeout().toMillis())
                .responseTimeout(properties.esi().readTimeout());

        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs()
                        .maxInMemorySize((int) properties.esi().maxInMemorySize().toBytes()))
                .build();

        return WebClient.builder()
                .baseUrl(properties.esi().baseUrl())
                .defaultHeader(HttpHeaders.USER_AGENT, properties.esi().userAgent())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(strategies)
                .filter(errorBudgetFilter)
                .build();
    }

    /**
     * Pre-configured OpenAPI client using the reactive WebClient.
     */
    @Bean
    public ApiClient esiApiClient(WebClient esiWebClient) {
        ApiClient apiClient = new ApiClient(esiWebClient);
        apiClient.setBasePath(properties.esi().baseUrl());
        return apiClient;
    }

    /**
     * OpenAPI generated Market API client for market orders and history.
     */
    @Bean
    public MarketApi marketApi(ApiClient esiApiClient) {
        return new MarketApi(esiApiClient);
    }
}
