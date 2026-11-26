package com.flashseat.order.outbox;

import com.flashseat.order.TestcontainersConfiguration;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OutboxPublisherTest {

    @Autowired
    private OutboxPublisher publisher;

    @Autowired
    private OutboxProperties properties;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private KafkaContainer kafka;

    @Test
    void publishedEventCarriesKeyHeadersAndPayload() {
        String orderId = "order-" + UUID.randomUUID();
        UUID eventId = insertEvent(orderId, "OrderCreated", "{\"amountCents\": 5000}", Instant.now());

        publisher.publishPending();

        ConsumerRecord<String, String> record = readEventsOf(Set.of(orderId), 1).getFirst();
        assertThat(record.key()).isEqualTo(orderId);
        assertThat(header(record, OutboxPublisher.EVENT_ID_HEADER)).isEqualTo(eventId.toString());
        assertThat(header(record, OutboxPublisher.EVENT_TYPE_HEADER)).isEqualTo("OrderCreated");
        assertThat(record.value()).contains("\"amountCents\": 5000");
        assertThat(isPublished(eventId)).isTrue();
    }

    @Test
    void eventsOfOneOrderArriveInTheOrderTheyHappened() {
        String orderId = "order-" + UUID.randomUUID();
        Instant start = Instant.now();
        insertEvent(orderId, "OrderCreated", "{}", start);
        insertEvent(orderId, "OrderPaid", "{}", start.plusMillis(1));
        insertEvent(orderId, "OrderTicketed", "{}", start.plusMillis(2));

        publisher.publishPending();

        List<ConsumerRecord<String, String>> records = readEventsOf(Set.of(orderId), 3);
        assertThat(records).extracting(r -> header(r, OutboxPublisher.EVENT_TYPE_HEADER))
                .containsExactly("OrderCreated", "OrderPaid", "OrderTicketed");
        assertThat(records).extracting(ConsumerRecord::partition).containsOnly(records.getFirst().partition());
    }

    @Test
    void eventsStayInTheOutboxWhileKafkaIsDownAndGoOutLater() {
        String orderId = "order-" + UUID.randomUUID();
        UUID eventId = insertEvent(orderId, "OrderCreated", "{}", Instant.now());
        OutboxPublisher publisherWithDeadKafka = new OutboxPublisher(jdbc, transactionManager, unreachableKafka(), properties);

        assertThatThrownBy(publisherWithDeadKafka::publishPending).isInstanceOf(OutboxPublishException.class);
        assertThat(isPublished(eventId)).isFalse();

        publisher.publishPending();

        assertThat(isPublished(eventId)).isTrue();
        assertThat(readEventsOf(Set.of(orderId), 1)).hasSize(1);
    }

    @Test
    void worksThroughSeveralBatches() {
        Set<String> orderIds = insertManyOrders(12);

        publisher.publishPending();

        assertThat(readEventsOf(orderIds, 12)).hasSize(12);
    }

    @Test
    void onlyOnePublisherRunsAtATime() throws Exception {
        String orderId = "order-" + UUID.randomUUID();
        UUID eventId = insertEvent(orderId, "OrderCreated", "{}", Instant.now());

        CountDownLatch otherReplicaHasLock = new CountDownLatch(1);
        CountDownLatch otherReplicaMayFinish = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.sql("SELECT pg_advisory_xact_lock(7340002)").query().singleRow();
                otherReplicaHasLock.countDown();
                awaitQuietly(otherReplicaMayFinish);
            }));

            otherReplicaHasLock.await();
            assertThat(publisher.publishPending()).isZero();
            assertThat(isPublished(eventId)).isFalse();
            otherReplicaMayFinish.countDown();
        }

        publisher.publishPending();
        assertThat(isPublished(eventId)).isTrue();
    }

    @Test
    void manyPublishersAtOnceSendEachEventExactlyOnce() throws Exception {
        Set<String> orderIds = insertManyOrders(20);

        List<Future<Integer>> runs = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int replica = 0; replica < 4; replica++) {
                runs.add(executor.submit(publisher::publishPending));
            }
        }
        for (Future<Integer> run : runs) {
            run.get();
        }
        publisher.publishPending();

        List<ConsumerRecord<String, String>> records = readEventsOf(orderIds, 20);
        Set<String> distinctEventIds = records.stream()
                .map(r -> header(r, OutboxPublisher.EVENT_ID_HEADER))
                .collect(Collectors.toSet());
        assertThat(records).hasSize(20);
        assertThat(distinctEventIds).hasSize(20);
    }

    private Set<String> insertManyOrders(int count) {
        Set<String> orderIds = new HashSet<>();
        for (int i = 0; i < count; i++) {
            String orderId = "order-" + UUID.randomUUID();
            insertEvent(orderId, "OrderCreated", "{}", Instant.now());
            orderIds.add(orderId);
        }
        return orderIds;
    }

    private UUID insertEvent(String aggregateId, String type, String payload, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type, payload, created_at)
                        VALUES (:id, 'order', :aggregateId, :type, CAST(:payload AS jsonb), :createdAt)
                        """)
                .param("id", id)
                .param("aggregateId", aggregateId)
                .param("type", type)
                .param("payload", payload)
                .param("createdAt", createdAt.atOffset(ZoneOffset.UTC))
                .update();
        return id;
    }

    private boolean isPublished(UUID eventId) {
        return jdbc.sql("SELECT published_at IS NOT NULL FROM outbox WHERE id = :id")
                .param("id", eventId)
                .query(Boolean.class)
                .single();
    }

    /** Reads the topic from the beginning and keeps only the records of the given orders, in arrival order. */
    private List<ConsumerRecord<String, String>> readEventsOf(Set<String> orderIds, int expected) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer =
                     new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(properties.topic()));
            Instant deadline = Instant.now().plusSeconds(20);
            while (found.size() < expected && Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (orderIds.contains(record.key())) {
                        found.add(record);
                    }
                }
            }
            // Keep reading briefly to catch any unexpected duplicates.
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofSeconds(1))) {
                if (orderIds.contains(record.key())) {
                    found.add(record);
                }
            }
        }
        return found;
    }

    private static KafkaTemplate<String, String> unreachableKafka() {
        Map<String, Object> config = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1",
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 1000,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(config));
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
