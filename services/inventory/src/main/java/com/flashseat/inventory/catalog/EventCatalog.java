package com.flashseat.inventory.catalog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads event pages from the read replica. Callers normally go through {@link EventPageCache}.
 * <p>
 * If the replica fails, pages are read from the primary, and the replica is left alone for a while
 * so that every request does not pay for the same timeout while it is down.
 */
@Component
public class EventCatalog {

    private static final Logger log = LoggerFactory.getLogger(EventCatalog.class);
    static final Duration REPLICA_RETRY_AFTER = Duration.ofSeconds(10);

    private final JdbcClient replica;
    private final JdbcClient primary;
    private final InstantSource clock;
    private volatile Instant skipReplicaUntil = Instant.MIN;

    public EventCatalog(@Qualifier("replicaJdbc") JdbcClient replica, JdbcClient primary, InstantSource clock) {
        this.replica = replica;
        this.primary = primary;
        this.clock = clock;
    }

    public Optional<EventPage> loadEventPage(UUID eventId) {
        if (clock.instant().isAfter(skipReplicaUntil)) {
            try {
                return loadFrom(replica, eventId);
            } catch (DataAccessException e) {
                skipReplicaUntil = clock.instant().plus(REPLICA_RETRY_AFTER);
                log.warn("Read replica unavailable, reading event pages from the primary for {}: {}",
                        REPLICA_RETRY_AFTER, e.getMessage());
            }
        }
        return loadFrom(primary, eventId);
    }

    private static Optional<EventPage> loadFrom(JdbcClient jdbc, UUID eventId) {
        List<EventPage.Seat> seats = jdbc.sql("""
                        SELECT seat_id, section, price_cents,
                               CASE WHEN status = 'AVAILABLE' THEN 'AVAILABLE' ELSE 'UNAVAILABLE' END AS shown_status
                        FROM seats WHERE event_id = :eventId
                        ORDER BY section, seat_id
                        """)
                .param("eventId", eventId)
                .query((row, rowNum) -> new EventPage.Seat(
                        row.getString("seat_id"),
                        row.getString("section"),
                        row.getLong("price_cents"),
                        row.getString("shown_status")))
                .list();
        long available = seats.stream().filter(seat -> seat.status().equals("AVAILABLE")).count();

        return jdbc.sql("SELECT id, name, venue, starts_at, sale_opens_at FROM events WHERE id = :eventId")
                .param("eventId", eventId)
                .query((row, rowNum) -> new EventPage(
                        row.getObject("id", UUID.class),
                        row.getString("name"),
                        row.getString("venue"),
                        row.getObject("starts_at", OffsetDateTime.class).toInstant(),
                        row.getObject("sale_opens_at", OffsetDateTime.class).toInstant(),
                        available,
                        seats))
                .optional();
    }
}
