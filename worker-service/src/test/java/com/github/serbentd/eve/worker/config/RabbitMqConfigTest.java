package com.github.serbentd.eve.worker.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.serbentd.eve.worker.event.MarketOrderEvent;
import com.github.serbentd.eve.worker.event.RegionSnapshotCompletedEvent;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RabbitMqConfigTest {

    private static final long THE_FORGE_REGION_ID = 10000002L;
    private static final long TRITANIUM_TYPE_ID = 34L;
    private static final long SAMPLE_ORDER_ID_1 = 1001L;
    private static final long SAMPLE_ORDER_ID_2 = 2001L;

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
    void jsonMessageConverter_shouldDeserializeOrderListWithoutTypeErasureToLinkedHashMap() {
        MessageConverter converter = config.jsonMessageConverter(new ObjectMapper());

        String jsonList = """
                [
                  {
                    "orderId": 1001,
                    "typeId": 34,
                    "regionId": 10000002,
                    "systemId": 30000142,
                    "locationId": 60003760,
                    "price": 5.25,
                    "volumeRemain": 1000,
                    "volumeTotal": 5000,
                    "minVolume": 1,
                    "duration": 90,
                    "isBuyOrder": false,
                    "orderRange": "region",
                    "snapshotId": "snap-123",
                    "issued": "2026-09-10T12:00:00Z",
                    "polledAt": "2026-09-10T12:05:00Z"
                  }
                ]
                """;

        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setHeader("__TypeId__", "java.util.ArrayList");

        Message message = new Message(jsonList.getBytes(StandardCharsets.UTF_8), props);

        Object result = converter.fromMessage(message);

        assertThat(result).isInstanceOf(List.class);
        List<?> list = (List<?>) result;
        assertThat(list).hasSize(1);
        assertThat(list.get(0)).isInstanceOf(MarketOrderEvent.class);
        MarketOrderEvent event = (MarketOrderEvent) list.get(0);
        assertThat(event.orderId()).isEqualTo(SAMPLE_ORDER_ID_1);
        assertThat(event.typeId()).isEqualTo(TRITANIUM_TYPE_ID);
    }

    @Test
    void jsonMessageConverter_shouldDeserializeSnapshotCompletedEvent() {
        MessageConverter converter = config.jsonMessageConverter(new ObjectMapper());

        String json = """
                {
                  "regionId": 10000002,
                  "snapshotId": "snap-123",
                  "totalOrders": 45000,
                  "completedAt": "2026-09-10T12:05:00Z"
                }
                """;

        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setHeader("__TypeId__", "com.github.serbentd.eve.poller.event.RegionSnapshotCompletedEvent");

        Message message = new Message(json.getBytes(StandardCharsets.UTF_8), props);

        Object result = converter.fromMessage(message);

        assertThat(result).isInstanceOf(RegionSnapshotCompletedEvent.class);
        RegionSnapshotCompletedEvent event = (RegionSnapshotCompletedEvent) result;
        assertThat(event.regionId()).isEqualTo(THE_FORGE_REGION_ID);
        assertThat(event.snapshotId()).isEqualTo("snap-123");
    }

    @Test
    void jsonMessageConverter_shouldDeserializeSingleMarketOrderEvent() {
        MessageConverter converter = config.jsonMessageConverter(new ObjectMapper());

        String json = """
                {
                  "orderId": 2001,
                  "typeId": 34,
                  "regionId": 10000002,
                  "systemId": 30000142,
                  "locationId": 60003760,
                  "price": 6.50,
                  "volumeRemain": 500,
                  "volumeTotal": 1000,
                  "minVolume": 1,
                  "duration": 90,
                  "isBuyOrder": true,
                  "orderRange": "region",
                  "snapshotId": "snap-200",
                  "issued": "2026-09-10T12:00:00Z",
                  "polledAt": "2026-09-10T12:05:00Z"
                }
                """;

        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setHeader("__TypeId__", "com.github.serbentd.eve.poller.event.MarketOrderEvent");

        Message message = new Message(json.getBytes(StandardCharsets.UTF_8), props);

        Object result = converter.fromMessage(message);

        assertThat(result).isInstanceOf(MarketOrderEvent.class);
        MarketOrderEvent event = (MarketOrderEvent) result;
        assertThat(event.orderId()).isEqualTo(SAMPLE_ORDER_ID_2);
        assertThat(event.price()).isEqualByComparingTo("6.50");
    }

    @Test
    void jsonMessageConverter_whenHeaderMissing_shouldDefaultToOrderList() {
        MessageConverter converter = config.jsonMessageConverter(new ObjectMapper());

        String jsonList = "[]";
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        // Note: no __TypeId__ header set

        Message message = new Message(jsonList.getBytes(StandardCharsets.UTF_8), props);

        Object result = converter.fromMessage(message);

        assertThat(result).isInstanceOf(List.class);
        List<?> list = (List<?>) result;
        assertThat(list).isEmpty();
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
