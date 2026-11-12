package com.github.serbentd.eve.worker.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RabbitMQ Definitions & Least-Privilege Topology Validation")
class RabbitMqTopologyDefinitionTest {

    private static final String DEFINITIONS_PATH = "infrastructure/rabbitmq/definitions.json";

    @Test
    @DisplayName("Should validate /eve_market vhost, scoped users, and least-privilege permissions in definitions.json")
    void shouldValidateLeastPrivilegeBrokerTopology() throws Exception {
        Path path = resolvePath(DEFINITIONS_PATH);
        ObjectMapper mapper = new ObjectMapper();

        JsonNode root;
        try (InputStream is = Files.newInputStream(path)) {
            root = mapper.readTree(is);
        }

        // 1. VHosts
        JsonNode vhostsNode = root.get("vhosts");
        assertThat(vhostsNode).isNotNull();
        List<String> vhosts = new ArrayList<>();
        vhostsNode.forEach(v -> vhosts.add(v.get("name").asText()));
        assertThat(vhosts).contains("/eve_market");

        // 2. Scoped Users
        JsonNode usersNode = root.get("users");
        assertThat(usersNode).isNotNull();
        List<String> users = new ArrayList<>();
        usersNode.forEach(u -> users.add(u.get("name").asText()));
        assertThat(users).contains("poller_producer", "worker_consumer", "batch_broker");

        // 3. Least-Privilege Permissions on /eve_market
        JsonNode permsNode = root.get("permissions");
        assertThat(permsNode).isNotNull();

        boolean pollerVerified = false;
        boolean workerVerified = false;
        boolean batchVerified = false;

        for (JsonNode perm : permsNode) {
            String vhost = perm.get("vhost").asText();
            String user = perm.get("user").asText();
            if (!"/eve_market".equals(vhost)) {
                continue;
            }

            if ("poller_producer".equals(user)) {
                // Producers must hold write-only publication rights on exchanges, cannot consume or configure
                assertThat(perm.get("configure").asText()).isEqualTo("^$");
                assertThat(perm.get("write").asText()).isEqualTo("^eve\\.market\\.orders$");
                assertThat(perm.get("read").asText()).isEqualTo("^$");
                pollerVerified = true;
            } else if ("worker_consumer".equals(user)) {
                // Consumers must hold read-only consumption rights on their specific queues, cannot publish or configure
                assertThat(perm.get("configure").asText()).isEqualTo("^$");
                assertThat(perm.get("write").asText()).isEqualTo("^$");
                assertThat(perm.get("read").asText()).isEqualTo("^eve\\.market\\.orders\\.worker$");
                workerVerified = true;
            } else if ("batch_broker".equals(user)) {
                // Scoped to distributed batch partition exchange and queues
                assertThat(perm.get("configure").asText()).contains("eve\\.batch\\.partition");
                assertThat(perm.get("write").asText()).contains("eve\\.batch\\.partition");
                assertThat(perm.get("read").asText()).contains("eve\\.batch\\.partition");
                batchVerified = true;
            }
        }

        assertThat(pollerVerified).withFailMessage("poller_producer permissions not found on /eve_market").isTrue();
        assertThat(workerVerified).withFailMessage("worker_consumer permissions not found on /eve_market").isTrue();
        assertThat(batchVerified).withFailMessage("batch_broker permissions not found on /eve_market").isTrue();

        // 4. Exchanges & Dead-Letter Exchanges
        JsonNode exchangesNode = root.get("exchanges");
        assertThat(exchangesNode).isNotNull();
        List<String> exchanges = new ArrayList<>();
        exchangesNode.forEach(e -> exchanges.add(e.get("name").asText()));
        assertThat(exchanges).contains(
                "eve.market.orders",
                "eve.market.orders.dlx",
                "eve.batch.partition.exchange",
                "eve.batch.partition.dlx"
        );

        // 5. Queues & Dead-Letter Configuration
        JsonNode queuesNode = root.get("queues");
        assertThat(queuesNode).isNotNull();
        boolean workerQueueVerified = false;
        boolean batchQueueVerified = false;

        for (JsonNode q : queuesNode) {
            String qName = q.get("name").asText();
            if ("eve.market.orders.worker".equals(qName)) {
                JsonNode args = q.get("arguments");
                assertThat(args.get("x-dead-letter-exchange").asText()).isEqualTo("eve.market.orders.dlx");
                assertThat(args.get("x-dead-letter-routing-key").asText()).isEqualTo("eve.market.orders.dlq");
                workerQueueVerified = true;
            } else if ("eve.batch.partition.requests".equals(qName)) {
                JsonNode args = q.get("arguments");
                assertThat(args.get("x-dead-letter-exchange").asText()).isEqualTo("eve.batch.partition.dlx");
                assertThat(args.get("x-dead-letter-routing-key").asText()).isEqualTo("eve.batch.partition.dlq");
                batchQueueVerified = true;
            }
        }

        assertThat(workerQueueVerified).withFailMessage("eve.market.orders.worker DLQ configuration missing").isTrue();
        assertThat(batchQueueVerified).withFailMessage("eve.batch.partition.requests DLQ configuration missing").isTrue();
    }

    private Path resolvePath(String relativePath) {
        Path direct = Path.of(relativePath);
        if (Files.exists(direct)) {
            return direct;
        }
        Path parent = Path.of("..", relativePath);
        if (Files.exists(parent)) {
            return parent;
        }
        throw new IllegalArgumentException("Unable to resolve path: " + relativePath);
    }
}
