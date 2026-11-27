package com.flashseat.order.outbox;

import com.flashseat.order.TestcontainersConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OutboxMetricsTest {

    @Autowired
    private OutboxWriter outbox;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private MeterRegistry metrics;

    @Test
    void eventsAreCountedOnlyWhenTheirTransactionCommits() {
        double before = paidEvents();

        transactions.executeWithoutResult(tx -> outbox.append("order", UUID.randomUUID().toString(), "OrderPaid", Map.of()));
        transactions.executeWithoutResult(tx -> {
            outbox.append("order", UUID.randomUUID().toString(), "OrderPaid", Map.of());
            tx.setRollbackOnly();
        });

        assertThat(paidEvents()).isEqualTo(before + 1);
    }

    @Test
    void backlogGaugesShowWaitingEventsAndTheOldestAge() {
        double pendingBefore = gauge("flashseat.outbox.pending");
        UUID stuckEvent = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type, payload, created_at)
                        VALUES (:id, 'order', 'stuck-order', 'OrderPaid', '{}', now() - interval '90 seconds')
                        """)
                .param("id", stuckEvent)
                .update();
        try {
            assertThat(gauge("flashseat.outbox.pending")).isEqualTo(pendingBefore + 1);
            assertThat(gauge("flashseat.outbox.oldest.age")).isGreaterThanOrEqualTo(90);
        } finally {
            jdbc.sql("DELETE FROM outbox WHERE id = :id").param("id", stuckEvent).update();
        }
    }

    private double paidEvents() {
        return metrics.counter("flashseat.order.events", "type", "OrderPaid").count();
    }

    private double gauge(String name) {
        return metrics.get(name).gauge().value();
    }
}
