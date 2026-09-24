package com.github.serbentd.eve.poller.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AMQP configuration for market order message publication.
 * <p>
 * Declares the durable market orders {@link TopicExchange} and configures
 * {@link RabbitTemplate} with JSON serialization supporting Java 21 records
 * and ISO-8601 {@code OffsetDateTime} formatting.
 */
@Configuration
public class RabbitMqConfig {

    private final PollerProperties properties;

    public RabbitMqConfig(PollerProperties properties) {
        this.properties = properties;
    }

    /**
     * JSON message converter supporting Java records and ISO-8601 {@code OffsetDateTime} formatting.
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
     * Durable topic exchange for publishing market order events.
     */
    @Bean
    public TopicExchange marketOrdersExchange() {
        return new TopicExchange(properties.amqp().exchange(), true, false);
    }

    /**
     * Pre-configured RabbitTemplate using the JSON message converter.
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter jsonMessageConverter) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(jsonMessageConverter);
        return rabbitTemplate;
    }
}
