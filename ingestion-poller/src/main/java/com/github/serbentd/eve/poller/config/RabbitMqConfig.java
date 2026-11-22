package com.github.serbentd.eve.poller.config;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spring AMQP configuration for market order message publication.
 * <p>
 * Declares the durable market orders {@link TopicExchange} and configures
 * {@link RabbitTemplate} with JSON serialization supporting Java 21 records
 * and ISO-8601 {@code OffsetDateTime} formatting. Configures {@link RabbitAdmin}
 * with declaration exception tolerance for least-privilege producer roles.
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
     * @param jsonMapper auto-configured Spring Jackson 3 JSON mapper
     * @return configured JacksonJsonMessageConverter
     */
    @Bean
    public MessageConverter jsonMessageConverter(JsonMapper jsonMapper) {
        return new JacksonJsonMessageConverter(jsonMapper);
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

    /**
     * Configures {@link RabbitAdmin} with declaration exception tolerance, ensuring that least-privilege
     * producer accounts start cleanly without administrative declare permissions when topology is pre-provisioned.
     */
    @Bean
    public RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        admin.setIgnoreDeclarationExceptions(true);
        return admin;
    }
}
