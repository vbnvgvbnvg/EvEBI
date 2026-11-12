package com.github.serbentd.eve.batch.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.serbentd.eve.batch.job.daily.DailyMarketSummaryJobConfig;
import com.github.serbentd.eve.batch.job.liquidity.OrderBookLiquidityJobConfig;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.batch.integration.partition.StepExecutionRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.amqp.dsl.Amqp;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.messaging.MessageChannel;

/**
 * Spring AMQP and Spring Integration configuration for Spring Batch distributed remote partitioning.
 */
@Configuration
public class BatchAmqpConfig {

    public static final String OUTBOUND_CHANNEL = "partitionRequestsOutboundChannel";
    public static final String DAILY_INBOUND_CHANNEL = "dailyPartitionRequestsInboundChannel";
    public static final String LIQUIDITY_INBOUND_CHANNEL = "liquidityPartitionRequestsInboundChannel";

    public static final String REQUESTS_QUEUE_BEAN = "batchPartitionRequestsQueue";
    public static final String DLQ_BEAN = "batchPartitionDeadLetterQueue";

    /** Outbound publishing is registered only on manager nodes when AMQP flows are enabled. */
    static final String MANAGER_FLOW_CONDITION =
            "${eve.batch.remote-partitioning.amqp-flows-enabled} and '${eve.batch.remote-partitioning.role}' == 'MANAGER'";

    /** Inbound consumption is registered only on worker nodes, so managers never execute partitions. */
    static final String WORKER_FLOW_CONDITION =
            "${eve.batch.remote-partitioning.amqp-flows-enabled} and '${eve.batch.remote-partitioning.role}' == 'WORKER'";

    /**
     * Restricts Jackson type resolution (via the {@code __TypeId__} header) to the Spring Batch partition
     * payload package, preventing deserialization of arbitrary classes from broker messages.
     */
    private static final String TRUSTED_PAYLOAD_PACKAGE = StepExecutionRequest.class.getPackageName();

    /**
     * Message converter supporting Java records and {@link StepExecutionRequest} Jackson Mixin.
     */
    @Bean
    public MessageConverter batchJsonMessageConverter(ObjectMapper objectMapper) {
        ObjectMapper mapper = objectMapper.copy()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .addMixIn(StepExecutionRequest.class, StepExecutionRequestMixin.class);
        return new Jackson2JsonMessageConverter(mapper, TRUSTED_PAYLOAD_PACKAGE);
    }

    /**
     * Topic exchange for partitioned step execution requests.
     */
    @Bean
    public TopicExchange batchPartitionTopicExchange(BatchProperties properties) {
        return new TopicExchange(properties.remotePartitioning().exchange(), true, false);
    }

    /**
     * Durable queue for worker partition requests, routed to the dead-letter exchange on rejection.
     */
    @Bean(name = REQUESTS_QUEUE_BEAN)
    public Queue batchPartitionRequestsQueue(BatchProperties properties) {
        return QueueBuilder.durable(properties.remotePartitioning().requestsQueue())
                .deadLetterExchange(properties.remotePartitioning().deadLetterExchange())
                .deadLetterRoutingKey(properties.remotePartitioning().deadLetterRoutingKey())
                .build();
    }

    /**
     * Binds the partition requests queue to the topic exchange.
     */
    @Bean
    public Binding batchPartitionRequestsBinding(
            @Qualifier(REQUESTS_QUEUE_BEAN) Queue batchPartitionRequestsQueue,
            TopicExchange batchPartitionTopicExchange,
            BatchProperties properties) {
        return BindingBuilder.bind(batchPartitionRequestsQueue)
                .to(batchPartitionTopicExchange)
                .with(properties.remotePartitioning().requestsRoutingKey());
    }

    /**
     * Durable direct dead-letter exchange receiving rejected or unprocessable partition requests.
     */
    @Bean
    public DirectExchange batchPartitionDeadLetterExchange(BatchProperties properties) {
        return new DirectExchange(properties.remotePartitioning().deadLetterExchange(), true, false);
    }

    /**
     * Durable dead-letter queue storing failed partition requests for inspection and alerting.
     */
    @Bean(name = DLQ_BEAN)
    public Queue batchPartitionDeadLetterQueue(BatchProperties properties) {
        return QueueBuilder.durable(properties.remotePartitioning().deadLetterQueue())
                .build();
    }

    /**
     * Binds the dead-letter queue to the dead-letter exchange.
     */
    @Bean
    public Binding batchPartitionDeadLetterBinding(
            @Qualifier(DLQ_BEAN) Queue batchPartitionDeadLetterQueue,
            DirectExchange batchPartitionDeadLetterExchange,
            BatchProperties properties) {
        return BindingBuilder.bind(batchPartitionDeadLetterQueue)
                .to(batchPartitionDeadLetterExchange)
                .with(properties.remotePartitioning().deadLetterRoutingKey());
    }

    /**
     * Outbound channel where the Manager sends StepExecutionRequest messages.
     */
    @Bean(name = OUTBOUND_CHANNEL)
    public DirectChannel partitionRequestsOutboundChannel() {
        return new DirectChannel();
    }

    /**
     * Inbound channel where daily summary StepExecutionRequest messages are routed to worker step.
     */
    @Bean(name = DAILY_INBOUND_CHANNEL)
    public DirectChannel dailyPartitionRequestsInboundChannel() {
        return new DirectChannel();
    }

    /**
     * Inbound channel where order book liquidity StepExecutionRequest messages are routed to worker step.
     */
    @Bean(name = LIQUIDITY_INBOUND_CHANNEL)
    public DirectChannel liquidityPartitionRequestsInboundChannel() {
        return new DirectChannel();
    }

    /**
     * Outbound AMQP flow publishing partition requests from the Manager to RabbitMQ.
     */
    @Bean
    @ConditionalOnExpression(MANAGER_FLOW_CONDITION)
    public IntegrationFlow partitionOutboundFlow(
            AmqpTemplate amqpTemplate,
            BatchProperties batchProperties,
            @Qualifier(OUTBOUND_CHANNEL) MessageChannel partitionRequestsOutboundChannel) {

        return IntegrationFlow.from(partitionRequestsOutboundChannel)
                .handle(Amqp.outboundAdapter(amqpTemplate)
                        .exchangeName(batchProperties.remotePartitioning().exchange())
                        .routingKey(batchProperties.remotePartitioning().requestsRoutingKey()))
                .get();
    }

    /**
     * Inbound AMQP flow consuming partition requests from RabbitMQ and routing by stepName to dedicated worker channels.
     */
    @Bean
    @ConditionalOnExpression(WORKER_FLOW_CONDITION)
    public IntegrationFlow partitionInboundFlow(
            ConnectionFactory connectionFactory,
            BatchProperties batchProperties,
            MessageConverter batchJsonMessageConverter) {

        return IntegrationFlow.from(Amqp.inboundAdapter(connectionFactory, batchProperties.remotePartitioning().requestsQueue())
                        .configureContainer(container -> container
                                .prefetchCount(batchProperties.remotePartitioning().prefetchCount())
                                .concurrentConsumers(batchProperties.remotePartitioning().workerConcurrency())
                                .maxConcurrentConsumers(batchProperties.remotePartitioning().workerConcurrency())
                                .shutdownTimeout(batchProperties.asyncLauncher().awaitTermination().toMillis())
                                .defaultRequeueRejected(false))
                        .messageConverter(batchJsonMessageConverter))
                .route(StepExecutionRequest.class, StepExecutionRequest::getStepName, mapping -> mapping
                        .channelMapping(DailyMarketSummaryJobConfig.WORKER_STEP_NAME, DAILY_INBOUND_CHANNEL)
                        .channelMapping(OrderBookLiquidityJobConfig.WORKER_STEP_NAME, LIQUIDITY_INBOUND_CHANNEL))
                .get();
    }
}
