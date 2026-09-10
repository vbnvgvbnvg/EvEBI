package com.github.serbentd.eve.worker.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AMQP configuration for market order message ingestion.
 * Binds the worker queue to the topic exchange using {@link MarketProperties},
 * and configures Jackson JSON serialization with Java 21 record and time support.
 */
@Configuration
@EnableConfigurationProperties(MarketProperties.class)
public class RabbitMqConfig {

    private final MarketProperties properties;

    public RabbitMqConfig(MarketProperties properties) {
        this.properties = properties;
    }

    /**
     * JSON message converter supporting Java records and {@code OffsetDateTime} serialization.
     *
     * @param objectMapper auto-configured Spring Jackson object mapper
     * @return configured Jackson2JsonMessageConverter
     */
    @Bean
    public MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        ObjectMapper mapper = objectMapper.copy()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return new Jackson2JsonMessageConverter(mapper);
    }

    /**
     * Durable topic exchange for market orders.
     */
    @Bean
    public TopicExchange marketOrdersExchange() {
        return new TopicExchange(properties.exchange(), true, false);
    }

    /**
     * Durable queue for worker service order persistence.
     */
    @Bean
    public Queue marketOrdersQueue() {
        return QueueBuilder.durable(properties.queue()).build();
    }

    /**
     * Binds the worker queue to the topic exchange using the configured routing key pattern.
     */
    @Bean
    public Binding marketOrdersBinding(Queue marketOrdersQueue, TopicExchange marketOrdersExchange) {
        return BindingBuilder.bind(marketOrdersQueue)
                .to(marketOrdersExchange)
                .with(properties.routingKey());
    }
}
