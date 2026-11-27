package com.flashseat.order.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * How far the outbox is behind. A growing backlog or an old waiting event means Kafka is down
 * or the publisher is stuck, long before buyers notice missing tickets.
 * Both values are read from the small partial index of unpublished rows on every scrape.
 */
@Component
public class OutboxMetrics {

    private final JdbcClient jdbc;

    public OutboxMetrics(JdbcClient jdbc, MeterRegistry metrics) {
        this.jdbc = jdbc;
        Gauge.builder("flashseat.outbox.pending", this::pendingEvents)
                .description("Events written but not yet published to Kafka")
                .register(metrics);
        Gauge.builder("flashseat.outbox.oldest.age", this::oldestPendingAgeSeconds)
                .description("Age of the oldest unpublished event")
                .baseUnit("seconds")
                .register(metrics);
    }

    long pendingEvents() {
        return jdbc.sql("SELECT count(*) FROM outbox WHERE published_at IS NULL")
                .query(Long.class)
                .single();
    }

    double oldestPendingAgeSeconds() {
        return jdbc.sql("""
                        SELECT coalesce(extract(epoch FROM now() - min(created_at)), 0)
                        FROM outbox WHERE published_at IS NULL
                        """)
                .query(Double.class)
                .single();
    }
}
