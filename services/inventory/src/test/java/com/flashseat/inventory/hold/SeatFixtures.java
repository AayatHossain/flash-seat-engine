package com.flashseat.inventory.hold;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** Creates a fresh event with seats for each test, so tests never share data. */
class SeatFixtures {

    private final JdbcClient jdbc;

    SeatFixtures(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    UUID eventWithSeats(String... seatIds) {
        UUID eventId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO events (id, name, venue, starts_at, sale_opens_at)
                        VALUES (:id, 'Test Concert', 'Test Arena', :startsAt, :saleOpensAt)
                        """)
                .param("id", eventId)
                .param("startsAt", Instant.parse("2027-01-10T19:00:00Z").atOffset(ZoneOffset.UTC))
                .param("saleOpensAt", Instant.parse("2026-12-01T10:00:00Z").atOffset(ZoneOffset.UTC))
                .update();

        for (String seatId : seatIds) {
            jdbc.sql("INSERT INTO seats (event_id, seat_id, section, price_cents) VALUES (:eventId, :seatId, 'A', 5000)")
                    .param("eventId", eventId)
                    .param("seatId", seatId)
                    .update();
        }
        return eventId;
    }

    String seatStatus(UUID eventId, String seatId) {
        return jdbc.sql("SELECT status FROM seats WHERE event_id = :eventId AND seat_id = :seatId")
                .param("eventId", eventId)
                .param("seatId", seatId)
                .query(String.class)
                .single();
    }

    UUID seatHoldId(UUID eventId, String seatId) {
        return jdbc.sql("SELECT hold_id FROM seats WHERE event_id = :eventId AND seat_id = :seatId")
                .param("eventId", eventId)
                .param("seatId", seatId)
                .query(UUID.class)
                .optional()
                .orElse(null);
    }

    String holdStatus(UUID eventId, UUID holdId) {
        return jdbc.sql("SELECT status FROM holds WHERE event_id = :eventId AND id = :holdId")
                .param("eventId", eventId)
                .param("holdId", holdId)
                .query(String.class)
                .single();
    }

    int holdCount(UUID eventId) {
        return jdbc.sql("SELECT count(*) FROM holds WHERE event_id = :eventId")
                .param("eventId", eventId)
                .query(Integer.class)
                .single();
    }

    List<String> seatsHeldBy(UUID eventId, UUID holdId) {
        return jdbc.sql("SELECT seat_id FROM seats WHERE event_id = :eventId AND hold_id = :holdId ORDER BY seat_id")
                .param("eventId", eventId)
                .param("holdId", holdId)
                .query(String.class)
                .list();
    }

    void expireNow(UUID eventId, UUID holdId) {
        jdbc.sql("UPDATE holds SET expires_at = now() - interval '1 second' WHERE event_id = :eventId AND id = :holdId")
                .param("eventId", eventId)
                .param("holdId", holdId)
                .update();
    }
}
