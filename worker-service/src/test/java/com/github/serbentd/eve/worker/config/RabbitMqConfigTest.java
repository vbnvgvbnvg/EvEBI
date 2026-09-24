package com.github.serbentd.eve.worker.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class RabbitMqConfigTest {

    private MarketProperties properties;
    private RabbitMqConfig config;

    @BeforeEach
    void setUp() {
        properties = new MarketProperties(
                "test.exchange",
                "test.queue",
                "test.orders.#",
                500,
                Duration.ofHours(2),
                1000000
        );
        config = new RabbitMqConfig(properties);
    }

    @Test
    void jsonMessageConverter_shouldSerializeOffsetDateTimeAsIsoString() {
        MessageConverter converter = config.jsonMessageConverter(new ObjectMapper());

        assertThat(converter).isInstanceOf(Jackson2JsonMessageConverter.class);

        record SamplePayload(String name, OffsetDateTime timestamp) {}
        OffsetDateTime testTime = OffsetDateTime.of(2026, 9, 10, 12, 0, 0, 0, ZoneOffset.UTC);
        SamplePayload payload = new SamplePayload("Jita", testTime);

        Message message = converter.toMessage(payload, new MessageProperties());
        String jsonBody = new String(message.getBody(), StandardCharsets.UTF_8);

        // Asserts that timestamps are serialized as ISO-8601 strings rather than epoch milliseconds
        assertThat(jsonBody).contains("\"timestamp\":\"2026-09-10T12:00:00Z\"");
    }

    @Test
    void marketOrdersBeans_shouldConfigureQueueExchangeAndBinding() {
        TopicExchange exchange = config.marketOrdersExchange();
        assertThat(exchange.getName()).isEqualTo("test.exchange");
        assertThat(exchange.isDurable()).isTrue();
        assertThat(exchange.isAutoDelete()).isFalse();

        Queue queue = config.marketOrdersQueue();
        assertThat(queue.getName()).isEqualTo("test.queue");
        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.getArguments().get("x-message-ttl")).isEqualTo(7200000);
        assertThat(queue.getArguments().get("x-max-length")).isEqualTo(1000000);
        assertThat(queue.getArguments().get("x-overflow")).isEqualTo("drop-head");

        Binding binding = config.marketOrdersBinding(queue, exchange);
        assertThat(binding.getDestination()).isEqualTo("test.queue");
        assertThat(binding.getExchange()).isEqualTo("test.exchange");
        assertThat(binding.getRoutingKey()).isEqualTo("test.orders.#");
    }
}
