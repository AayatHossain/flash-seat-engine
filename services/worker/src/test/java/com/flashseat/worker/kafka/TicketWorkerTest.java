package com.flashseat.worker.kafka;

import com.flashseat.worker.TestcontainersConfiguration;
import com.flashseat.worker.TestcontainersConfiguration.MailpitContainer;
import com.flashseat.worker.ticket.TicketProperties;
import com.flashseat.worker.ticket.TicketSigner;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** The worker end to end: real Kafka in, real Postgres for tickets, real SMTP (Mailpit) out. */
@SpringBootTest(properties = {
        "management.tracing.export.otlp.enabled=false",
        "management.opentelemetry.tracing.export.schedule-delay=100ms"})
@AutoConfigureTracing
@Import({TestcontainersConfiguration.class, TicketWorkerTest.CapturedSpans.class})
class TicketWorkerTest {

    /** Keeps finished spans in memory instead of sending them to a collector. */
    @TestConfiguration(proxyBeanMethods = false)
    static class CapturedSpans {
        @Bean
        InMemorySpanExporter inMemorySpanExporter() {
            return InMemorySpanExporter.create();
        }
    }

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private TicketProperties properties;

    @Autowired
    private TicketSigner signer;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private MailpitContainer mailpit;

    @Autowired
    private KafkaContainer kafkaContainer;

    @Autowired
    private JsonMapper json;

    @Autowired
    private MeterRegistry metrics;

    @Autowired
    private InMemorySpanExporter spans;

    @Test
    void paidOrderGetsOneSignedTicketPerSeatAndAnEmail() {
        PaidOrder order = new PaidOrder();
        double issuedBefore = count("flashseat.tickets.issued");

        publish(order.key(), "OrderPaid", "evt-" + UUID.randomUUID(), order.json("A-1", "A-2"));

        await().atMost(Duration.ofSeconds(30)).until(() -> ticketCount(order) == 2);
        await().atMost(Duration.ofSeconds(5)).until(() -> count("flashseat.tickets.issued") >= issuedBefore + 2);
        List<String> codes = jdbc.sql("SELECT code FROM tickets WHERE order_id = :orderId")
                .param("orderId", order.orderId())
                .query(String.class)
                .list();
        assertThat(codes).allMatch(signer::isGenuine);

        await().atMost(Duration.ofSeconds(15)).until(() -> emailsTo(order).size() == 1);
        JsonNode email = emailsTo(order).getFirst();
        assertThat(email.path("Subject").asString()).contains(order.orderId().toString());
        assertThat(email.path("Attachments").asInt()).isEqualTo(2);
    }

    @Test
    void ticketWorkContinuesTheTraceThatCameWithTheEvent() {
        PaidOrder order = new PaidOrder();
        String traceId = UUID.randomUUID().toString().replace("-", "");
        String parentSpanId = traceId.substring(0, 16);
        String traceparent = "00-" + traceId + "-" + parentSpanId + "-01";

        publish(order.key(), "OrderPaid", "evt-" + UUID.randomUUID(), order.json("A-1"), traceparent);

        await().atMost(Duration.ofSeconds(30)).until(() -> spansOfTrace(traceId).stream()
                .anyMatch(span -> parentSpanId.equals(span.getParentSpanId())));
        SpanData processing = spansOfTrace(traceId).stream()
                .filter(span -> parentSpanId.equals(span.getParentSpanId()))
                .findFirst().orElseThrow();
        assertThat(processing.getName()).isEqualTo(properties.topic() + " process");
        // The ticket inserts show up inside the same trace, under the processing span.
        assertThat(spansOfTrace(traceId)).anyMatch(span -> span.getName().equals("query"));
    }

    @Test
    void theSameEventDeliveredTwiceIssuesTicketsOnce() {
        PaidOrder order = new PaidOrder();
        String eventId = "evt-" + UUID.randomUUID();
        double duplicatesBefore = count("flashseat.events.duplicates");

        publish(order.key(), "OrderPaid", eventId, order.json("A-1"));
        publish(order.key(), "OrderPaid", eventId, order.json("A-1"));

        await().atMost(Duration.ofSeconds(30)).until(() -> inboxHas(eventId) && ticketCount(order) == 1);
        // Give the duplicate time to be consumed, then make sure it changed nothing.
        publish(order.key(), "OrderCreated", "evt-" + UUID.randomUUID(), "{}");
        await().pollDelay(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(10)).until(() -> true);
        assertThat(ticketCount(order)).isEqualTo(1);
        assertThat(emailsTo(order)).hasSize(1);
        assertThat(count("flashseat.events.duplicates")).isGreaterThanOrEqualTo(duplicatesBefore + 1);
    }

    @Test
    void otherEventTypesAreIgnored() {
        PaidOrder order = new PaidOrder();
        String createdEventId = "evt-" + UUID.randomUUID();
        String paidEventId = "evt-" + UUID.randomUUID();

        // Same key, so both land on the same partition and are consumed in this order.
        publish(order.key(), "OrderCreated", createdEventId, order.json("A-1"));
        publish(order.key(), "OrderPaid", paidEventId, order.json("A-1"));

        await().atMost(Duration.ofSeconds(30)).until(() -> inboxHas(paidEventId));
        assertThat(inboxHas(createdEventId)).isFalse();
        assertThat(ticketCount(order)).isEqualTo(1);
    }

    @Test
    void brokenEventIsParkedOnTheDeadLetterTopicAndDoesNotBlockTheNextOne() {
        PaidOrder order = new PaidOrder();
        String brokenEventId = "evt-" + UUID.randomUUID();
        double deadLetteredBefore = count("flashseat.events.dead_lettered");

        publish(order.key(), "OrderPaid", brokenEventId, "{ this is not json");
        publish(order.key(), "OrderPaid", "evt-" + UUID.randomUUID(), order.json("A-1"));

        await().atMost(Duration.ofSeconds(30)).until(() -> ticketCount(order) == 1);
        assertThat(deadLetterKeys(order.key())).containsExactly(order.key());
        assertThat(inboxHas(brokenEventId)).isFalse();
        assertThat(count("flashseat.events.dead_lettered")).isEqualTo(deadLetteredBefore + 1);
    }

    private double count(String counter) {
        return metrics.counter(counter).count();
    }

    private final class PaidOrder {
        private final UUID orderId = UUID.randomUUID();
        private final String userId = "buyer-" + UUID.randomUUID().toString().substring(0, 8);

        UUID orderId() {
            return orderId;
        }

        String key() {
            return orderId.toString();
        }

        String json(String... seats) {
            String seatList = String.join("\",\"", seats);
            return """
                    {"orderId":"%s","userId":"%s","eventId":"%s","holdId":"%s","seatIds":["%s"],
                     "amountCents":15000,"currency":"USD","paymentIntentId":"pi_test"}
                    """.formatted(orderId, userId, UUID.randomUUID(), UUID.randomUUID(), seatList);
        }
    }

    private void publish(String key, String eventType, String eventId, String payload) {
        publish(key, eventType, eventId, payload, null);
    }

    private void publish(String key, String eventType, String eventId, String payload, String traceparent) {
        ProducerRecord<String, String> record = new ProducerRecord<>(properties.topic(), key, payload);
        record.headers().add("event-type", eventType.getBytes(StandardCharsets.UTF_8));
        record.headers().add("event-id", eventId.getBytes(StandardCharsets.UTF_8));
        if (traceparent != null) {
            record.headers().add("traceparent", traceparent.getBytes(StandardCharsets.UTF_8));
        }
        kafka.send(record).join();
    }

    private List<SpanData> spansOfTrace(String traceId) {
        return spans.getFinishedSpanItems().stream().filter(span -> span.getTraceId().equals(traceId)).toList();
    }

    private int ticketCount(PaidOrder order) {
        return jdbc.sql("SELECT count(*) FROM tickets WHERE order_id = :orderId")
                .param("orderId", order.orderId())
                .query(Integer.class)
                .single();
    }

    private boolean inboxHas(String eventId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM inbox WHERE event_id = :eventId)")
                .param("eventId", eventId)
                .query(Boolean.class)
                .single();
    }

    private List<JsonNode> emailsTo(PaidOrder order) {
        String recipient = order.userId + "@" + properties.recipientDomain();
        String body = RestClient.create().get()
                .uri(mailpit.apiUrl() + "/search?query={query}", "to:\"" + recipient + "\"")
                .retrieve()
                .body(String.class);
        List<JsonNode> emails = new ArrayList<>();
        json.readTree(body).path("messages").forEach(emails::add);
        return emails;
    }

    private List<String> deadLetterKeys(String key) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-check-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        List<String> keys = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer =
                     new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(properties.deadLetterTopic()));
            Instant deadline = Instant.now().plusSeconds(15);
            while (keys.isEmpty() && Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (key.equals(record.key())) {
                        keys.add(record.key());
                    }
                }
            }
        }
        return keys;
    }
}
