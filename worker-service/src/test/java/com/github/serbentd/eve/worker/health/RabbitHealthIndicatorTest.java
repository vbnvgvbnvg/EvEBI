package com.github.serbentd.eve.worker.health;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.core.ChannelCallback;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.actuate.amqp.RabbitHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.net.ConnectException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class RabbitHealthIndicatorTest {

    private final RabbitTemplate rabbitTemplate;
    private final RabbitHealthIndicator healthIndicator;

    RabbitHealthIndicatorTest(@Mock RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
        this.healthIndicator = new RabbitHealthIndicator(rabbitTemplate);
    }

    @Test
    void health_whenBrokerReachable_shouldReturnStatusUpWithVersion() {
        given(rabbitTemplate.execute(any(ChannelCallback.class)))
                .willReturn("3.13.0");

        Health health = healthIndicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("version", "3.13.0");
    }

    @Test
    void health_whenBrokerUnreachable_shouldReturnStatusDown() {
        given(rabbitTemplate.execute(any(ChannelCallback.class)))
                .willThrow(new AmqpConnectException(new ConnectException("Connection refused")));

        Health health = healthIndicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("error");
    }
}
