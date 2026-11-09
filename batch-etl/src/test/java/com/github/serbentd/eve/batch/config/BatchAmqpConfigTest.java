package com.github.serbentd.eve.batch.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.batch.integration.partition.StepExecutionRequest;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.messaging.MessageChannel;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class BatchAmqpConfigTest {

    private static final String TEST_EXCHANGE = "test.exchange";
    private static final String TEST_QUEUE = "test.queue";
    private static final String TEST_ROUTING_KEY = "test.routingKey";
    private static final String TEST_DLX = "test.dlx";
    private static final String TEST_DLQ = "test.dlq";
    private static final String TEST_DLQ_ROUTING_KEY = "test.dlq.routingKey";
    private static final long THE_FORGE_REGION_ID = 10000002L;

    private final BatchAmqpConfig batchAmqpConfig = new BatchAmqpConfig();

    @Test
    @DisplayName("Should serialize and deserialize StepExecutionRequest with Jackson mixin")
    void shouldSerializeAndDeserializeStepExecutionRequest() {
        MessageConverter converter = batchAmqpConfig.batchJsonMessageConverter(new ObjectMapper());

        StepExecutionRequest request = new StepExecutionRequest("dailyMarketSummaryWorkerStep", 101L, 202L);
        Message message = converter.toMessage(request, new MessageProperties());

        String json = new String(message.getBody(), StandardCharsets.UTF_8);
        assertThat(json).contains("\"stepName\":\"dailyMarketSummaryWorkerStep\"");
        assertThat(json).contains("\"jobExecutionId\":101");
        assertThat(json).contains("\"stepExecutionId\":202");

        Object deserialized = converter.fromMessage(message);
        assertThat(deserialized).isInstanceOf(StepExecutionRequest.class);

        StepExecutionRequest result = (StepExecutionRequest) deserialized;
        assertThat(result.getStepName()).isEqualTo("dailyMarketSummaryWorkerStep");
        assertThat(result.getJobExecutionId()).isEqualTo(101L);
        assertThat(result.getStepExecutionId()).isEqualTo(202L);
    }

    @Test
    @DisplayName("Should reject payloads whose __TypeId__ header points outside the trusted package")
    void shouldRejectUntrustedPayloadTypes() {
        MessageConverter converter = batchJsonMessageConverterUnderTest();

        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setHeader("__TypeId__", "java.net.URL");
        Message message = new Message("\"http://localhost\"".getBytes(StandardCharsets.UTF_8), properties);

        assertThatThrownBy(() -> converter.fromMessage(message))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not in the trusted packages");
    }

    private MessageConverter batchJsonMessageConverterUnderTest() {
        return batchAmqpConfig.batchJsonMessageConverter(new ObjectMapper());
    }

    @Test
    @DisplayName("Should create durable topic exchange, queue with DLX arguments, and dead-letter declarables")
    void shouldCreateAmqpDeclarables() {
        BatchProperties properties = createTestProperties();

        TopicExchange exchange = batchAmqpConfig.batchPartitionTopicExchange(properties);
        assertThat(exchange.getName()).isEqualTo(TEST_EXCHANGE);
        assertThat(exchange.isDurable()).isTrue();

        Queue queue = batchAmqpConfig.batchPartitionRequestsQueue(properties);
        assertThat(queue.getName()).isEqualTo(TEST_QUEUE);
        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.getArguments())
                .containsEntry("x-dead-letter-exchange", TEST_DLX)
                .containsEntry("x-dead-letter-routing-key", TEST_DLQ_ROUTING_KEY);

        Binding binding = batchAmqpConfig.batchPartitionRequestsBinding(queue, exchange, properties);
        assertThat(binding.getDestination()).isEqualTo(TEST_QUEUE);
        assertThat(binding.getExchange()).isEqualTo(TEST_EXCHANGE);
        assertThat(binding.getRoutingKey()).isEqualTo(TEST_ROUTING_KEY);

        DirectExchange dlx = batchAmqpConfig.batchPartitionDeadLetterExchange(properties);
        assertThat(dlx.getName()).isEqualTo(TEST_DLX);
        assertThat(dlx.isDurable()).isTrue();

        Queue dlq = batchAmqpConfig.batchPartitionDeadLetterQueue(properties);
        assertThat(dlq.getName()).isEqualTo(TEST_DLQ);
        assertThat(dlq.isDurable()).isTrue();

        Binding dlqBinding = batchAmqpConfig.batchPartitionDeadLetterBinding(dlq, dlx, properties);
        assertThat(dlqBinding.getDestination()).isEqualTo(TEST_DLQ);
        assertThat(dlqBinding.getExchange()).isEqualTo(TEST_DLX);
        assertThat(dlqBinding.getRoutingKey()).isEqualTo(TEST_DLQ_ROUTING_KEY);
    }

    @Test
    @DisplayName("Should create direct channel beans with expected names")
    void shouldCreateChannels() {
        assertThat(batchAmqpConfig.partitionRequestsOutboundChannel()).isNotNull();
        assertThat(batchAmqpConfig.dailyPartitionRequestsInboundChannel()).isNotNull();
        assertThat(batchAmqpConfig.liquidityPartitionRequestsInboundChannel()).isNotNull();
    }

    @Test
    @DisplayName("Should create outbound integration flow bean")
    void shouldCreateOutboundFlow() {
        AmqpTemplate amqpTemplate = mock(AmqpTemplate.class);
        BatchProperties properties = createTestProperties();
        MessageChannel channel = new DirectChannel();

        IntegrationFlow flow = batchAmqpConfig.partitionOutboundFlow(amqpTemplate, properties, channel);
        assertThat(flow).isNotNull();
    }

    @Test
    @DisplayName("Should create inbound integration flow bean")
    void shouldCreateInboundFlow() {
        ConnectionFactory connectionFactory = mock(ConnectionFactory.class);
        BatchProperties properties = createTestProperties();
        MessageConverter converter = mock(MessageConverter.class);

        IntegrationFlow flow = batchAmqpConfig.partitionInboundFlow(connectionFactory, properties, converter);
        assertThat(flow).isNotNull();
    }

    private static BatchProperties createTestProperties() {
        return new BatchProperties(
                new BatchProperties.ChunkSizeProperties(10, 10),
                new BatchProperties.SdeProperties(
                        "http://localhost/items.csv",
                        "http://localhost/stations.csv",
                        "test-agent",
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(5),
                        10
                ),
                new BatchProperties.RemotePartitioningProperties(
                        PartitionRole.MANAGER,
                        true,
                        TEST_EXCHANGE,
                        TEST_QUEUE,
                        TEST_ROUTING_KEY,
                        TEST_DLX,
                        TEST_DLQ,
                        TEST_DLQ_ROUTING_KEY,
                        Duration.ofSeconds(5),
                        Duration.ofMinutes(10),
                        2,
                        1,
                        java.util.Map.of(THE_FORGE_REGION_ID, 2)
                )
        );
    }
}
