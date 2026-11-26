package com.flashseat.worker.ticket;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class Inbox {

    private final JdbcClient jdbc;

    public Inbox(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records the event as handled. Returns false if it was handled before, in which case the caller skips it.
     * Must run in the same transaction as the work, so the record and the work commit or roll back together.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean firstTime(String eventId, String eventType) {
        int inserted = jdbc.sql("""
                        INSERT INTO inbox (event_id, event_type) VALUES (:eventId, :eventType)
                        ON CONFLICT (event_id) DO NOTHING
                        """)
                .param("eventId", eventId)
                .param("eventType", eventType)
                .update();
        return inserted == 1;
    }
}
