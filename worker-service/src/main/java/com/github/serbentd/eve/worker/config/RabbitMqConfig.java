package com.github.serbentd.eve.worker.config;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.serbentd.eve.worker.event.MarketOrderEvent;
import com.github.serbentd.eve.worker.event.RegionSnapshotCompletedEvent;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Spring AMQP configuration for market order message ingestion.
 * Binds the worker queue to the topic exchange and configures dead-letter routing using {@link MarketProperties},
 * and configures Jackson JSON serialization with trusted package restrictions and Java 21 record support.
 */
@Configuration
@EnableConfigurationProperties(MarketProperties.class)
public class RabbitMqConfig {

    private static final String TRUSTED_WORKER_EVENT_PACKAGE = "com.github.serbentd.eve.worker.event";
    private static final String TRUSTED_POLLER_EVENT_PACKAGE = "com.github.serbentd.eve.poller.event";

    private final MarketProperties properties;

    public RabbitMqConfig(MarketProperties properties) {
        this.properties = properties;
    }

    /**
     * JSON message converter supporting Java records and {@code OffsetDateTime} serialization,
     * configured with a type mapper that maps JSON arrays to {@code List<MarketOrderEvent>} to prevent
     * type erasure to {@code LinkedHashMap}, and enforces trusted package deserialization.
     *
     * @param objectMapper auto-configured Spring Jackson object mapper
     * @return configured Jackson2JsonMessageConverter
     */
    @Bean
    public MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        ObjectMapper mapper = objectMapper.copy()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter(mapper);

        DefaultJackson2JavaTypeMapper typeMapper = new DefaultJackson2JavaTypeMapper() {
            private final JavaType orderListType = mapper.getTypeFactory()
                    .constructCollectionType(List.class, MarketOrderEvent.class);
            private final JavaType singleOrderType = mapper.getTypeFactory()
                    .constructType(MarketOrderEvent.class);
            private final JavaType snapshotType = mapper.getTypeFactory()
                    .constructType(RegionSnapshotCompletedEvent.class);

            @Override
            public JavaType toJavaType(MessageProperties properties) {
                String classId = (String) properties.getHeaders().get(DEFAULT_CLASSID_FIELD_NAME);
                if (classId != null) {
                    if (!classId.startsWith(TRUSTED_WORKER_EVENT_PACKAGE + ".")
                            && !classId.startsWith(TRUSTED_POLLER_EVENT_PACKAGE + ".")
                            && !classId.startsWith("java.util.")) {
                        throw new IllegalArgumentException(
                                "The class [" + classId + "] is not in the trusted packages: "
                                        + "[" + TRUSTED_WORKER_EVENT_PACKAGE + ", " + TRUSTED_POLLER_EVENT_PACKAGE + "]");
                    }
                    if (classId.endsWith(".RegionSnapshotCompletedEvent")) {
                        return snapshotType;
                    }
                    if (classId.endsWith(".MarketOrderEvent")) {
                        return singleOrderType;
                    }
                }
                return orderListType;
            }
        };

        typeMapper.setTrustedPackages(
                TRUSTED_WORKER_EVENT_PACKAGE,
                TRUSTED_POLLER_EVENT_PACKAGE
        );

        converter.setJavaTypeMapper(typeMapper);
        return converter;
    }

    /**
     * Durable topic exchange for market orders.
     */
    @Bean
    public TopicExchange marketOrdersExchange() {
        return new TopicExchange(properties.exchange(), true, false);
    }

    /**
     * Durable queue for worker service order persistence, configured with dead-letter exchange routing
     * for unprocessable or failed messages.
     */
    @Bean
    public Queue marketOrdersQueue() {
        return QueueBuilder.durable(properties.queue())
                .deadLetterExchange(properties.deadLetterExchange())
                .deadLetterRoutingKey(properties.deadLetterRoutingKey())
                .ttl((int) properties.queueTtl().toMillis())
                .maxLength(properties.queueMaxLength())
                .overflow(QueueBuilder.Overflow.dropHead)
                .build();
    }

    /**
     * Binds the worker queue to the topic exchange using the configured routing key pattern.
     */
    @Bean
    public Binding marketOrdersBinding(
            @Qualifier("marketOrdersQueue") Queue marketOrdersQueue,
            TopicExchange marketOrdersExchange) {
        return BindingBuilder.bind(marketOrdersQueue)
                .to(marketOrdersExchange)
                .with(properties.routingKey());
    }

    /**
     * Durable direct dead-letter exchange for failed or unprocessable market order messages.
     */
    @Bean
    public DirectExchange marketOrdersDeadLetterExchange() {
        return new DirectExchange(properties.deadLetterExchange(), true, false);
    }

    /**
     * Durable dead-letter queue storing failed market order events for diagnostics and replay.
     */
    @Bean
    public Queue marketOrdersDeadLetterQueue() {
        return QueueBuilder.durable(properties.deadLetterQueue()).build();
    }

    /**
     * Binds the dead-letter queue to the direct dead-letter exchange.
     */
    @Bean
    public Binding marketOrdersDeadLetterBinding(
            @Qualifier("marketOrdersDeadLetterQueue") Queue marketOrdersDeadLetterQueue,
            DirectExchange marketOrdersDeadLetterExchange) {
        return BindingBuilder.bind(marketOrdersDeadLetterQueue)
                .to(marketOrdersDeadLetterExchange)
                .with(properties.deadLetterRoutingKey());
    }

    /**
     * Configures {@link RabbitAdmin} with declaration exception tolerance, ensuring that least-privilege
     * application credentials without administrative declare permissions start cleanly when topology is pre-provisioned.
     */
    @Bean
    public RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        admin.setIgnoreDeclarationExceptions(true);
        return admin;
    }
}
