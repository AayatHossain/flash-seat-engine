package com.flashseat.inventory;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class SchemaMigrationTest {

    @Autowired
    private JdbcClient jdbc;

    @Test
    void holdsTableIsSplitIntoFourPartitions() {
        int partitions = jdbc.sql("SELECT count(*) FROM pg_inherits WHERE inhparent = 'holds'::regclass")
                .query(Integer.class)
                .single();

        assertThat(partitions).isEqualTo(4);
    }

    @Test
    void heldSeatPointsAtItsHold() {
        UUID eventId = insertEvent();
        UUID holdId = insertHold(eventId);

        insertSeat(eventId, "A-1", "HELD", holdId);

        String status = jdbc.sql("SELECT status FROM seats WHERE event_id = ? AND seat_id = 'A-1'")
                .param(eventId)
                .query(String.class)
                .single();
        assertThat(status).isEqualTo("HELD");
    }

    @Test
    void availableSeatCannotBelongToAHold() {
        UUID eventId = insertEvent();
        UUID holdId = insertHold(eventId);

        assertThatThrownBy(() -> insertSeat(eventId, "A-1", "AVAILABLE", holdId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void heldSeatMustBelongToAHold() {
        UUID eventId = insertEvent();

        assertThatThrownBy(() -> insertSeat(eventId, "A-1", "HELD", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void seatCannotPointAtAHoldThatDoesNotExist() {
        UUID eventId = insertEvent();

        assertThatThrownBy(() -> insertSeat(eventId, "A-1", "HELD", UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID insertEvent() {
        UUID eventId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO events (id, name, venue, starts_at, sale_opens_at)
                        VALUES (?, 'Test Concert', 'Test Arena', ?, ?)
                        """)
                .params(eventId, at("2027-01-10T19:00:00Z"), at("2026-12-01T10:00:00Z"))
                .update();
        return eventId;
    }

    private UUID insertHold(UUID eventId) {
        UUID holdId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO holds (id, event_id, user_id, status, expires_at)
                        VALUES (?, ?, 'user-1', 'ACTIVE', ?)
                        """)
                .params(holdId, eventId, at("2026-12-01T10:10:00Z"))
                .update();
        return holdId;
    }

    private void insertSeat(UUID eventId, String seatId, String status, UUID holdId) {
        jdbc.sql("""
                        INSERT INTO seats (event_id, seat_id, section, price_cents, status, hold_id)
                        VALUES (?, ?, 'A', 5000, ?, ?)
                        """)
                .params(eventId, seatId, status, holdId)
                .update();
    }

    private static OffsetDateTime at(String instant) {
        return Instant.parse(instant).atOffset(ZoneOffset.UTC);
    }
}
