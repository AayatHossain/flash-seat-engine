package com.flashseat.order.payment;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Remembers which provider events were fully handled. An event is recorded only after its work is done,
 * so a failure midway is retried by the provider instead of being skipped.
 */
@Component
public class WebhookEventLog {

    private final JdbcClient jdbc;

    public WebhookEventLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean alreadyProcessed(String eventId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM processed_webhook_events WHERE event_id = :eventId)")
                .param("eventId", eventId)
                .query(Boolean.class)
                .single();
    }

    public void markProcessed(PaymentEvent event) {
        jdbc.sql("""
                        INSERT INTO processed_webhook_events (event_id, event_type) VALUES (:eventId, :type)
                        ON CONFLICT (event_id) DO NOTHING
                        """)
                .param("eventId", event.eventId())
                .param("type", event.type().name())
                .update();
    }
}
