package com.flashseat.inventory.catalog;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Reads event pages straight from Postgres. Callers normally go through {@link EventPageCache}. */
@Component
public class EventCatalog {

    private final JdbcClient jdbc;

    public EventCatalog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<EventPage> loadEventPage(UUID eventId) {
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
