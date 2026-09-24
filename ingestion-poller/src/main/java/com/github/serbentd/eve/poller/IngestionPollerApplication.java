package com.github.serbentd.eve.poller;

import com.github.serbentd.eve.poller.config.PollerProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot main entry point for the EVE Online market order ingestion poller service.
 * <p>
 * Responsible for scheduled polling of CCP's ESI market endpoints and publishing
 * order snapshots to the RabbitMQ messaging broker.
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(PollerProperties.class)
public class IngestionPollerApplication {

    public static void main(String[] args) {
        SpringApplication.run(IngestionPollerApplication.class, args);
    }
}
