package com.github.serbentd.eve.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Eve-BI Worker Service.
 * <p>
 * Responsible for consuming EVE market order events from RabbitMQ,
 * validating payloads, and persisting raw market data to PostgreSQL.
 */
@SpringBootApplication
public class WorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkerApplication.class, args);
    }
}
