package com.flashseat.inventory.hold;

import com.flashseat.inventory.hold.HoldStateException.Problem;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Seat holds with Postgres as the single source of truth.
 * Every state change is a conditional UPDATE, so the database decides who wins a seat, never the application.
 */
@Service
public class HoldService {

    private final JdbcClient jdbc;
    private final HoldProperties properties;

    public HoldService(JdbcClient jdbc, HoldProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Transactional
    public Hold placeHold(UUID eventId, String userId, List<String> seatIds) {
        requireValidSeatList(seatIds);
        UUID holdId = UUID.randomUUID();

        OffsetDateTime expiresAt = jdbc.sql("""
                        INSERT INTO holds (id, event_id, user_id, status, expires_at)
                        VALUES (:holdId, :eventId, :userId, 'ACTIVE', now() + :seconds * interval '1 second')
                        RETURNING expires_at
                        """)
                .param("holdId", holdId)
                .param("eventId", eventId)
                .param("userId", userId)
                .param("seconds", properties.duration().toSeconds())
                .query(OffsetDateTime.class)
                .single();

        // Seats are locked in seat_id order, so two buyers who ask for the same seats
        // in a different order wait for each other instead of deadlocking.
        List<Long> claimedPrices = jdbc.sql("""
                        UPDATE seats
                        SET status = 'HELD', hold_id = :holdId, version = version + 1
                        WHERE (event_id, seat_id) IN (
                            SELECT event_id, seat_id
                            FROM seats
                            WHERE event_id = :eventId AND seat_id IN (:seatIds) AND status = 'AVAILABLE'
                            ORDER BY seat_id
                            FOR UPDATE)
                        RETURNING price_cents
                        """)
                .param("holdId", holdId)
                .param("eventId", eventId)
                .param("seatIds", seatIds)
                .query(Long.class)
                .list();

        if (claimedPrices.size() != seatIds.size()) {
            // Throwing rolls back the whole transaction, hold row included: the buyer gets all seats or none.
            throw new SeatsUnavailableException(eventId, seatIds);
        }
        long totalPriceCents = claimedPrices.stream().mapToLong(Long::longValue).sum();
        return new Hold(holdId, eventId, userId, "ACTIVE", List.copyOf(seatIds), totalPriceCents, expiresAt.toInstant());
    }

    public Optional<Hold> findHold(UUID eventId, UUID holdId) {
        List<HeldSeat> seats = jdbc.sql("""
                        SELECT seat_id, price_cents FROM seats
                        WHERE event_id = :eventId AND hold_id = :holdId
                        ORDER BY seat_id
                        """)
                .param("eventId", eventId)
                .param("holdId", holdId)
                .query((row, rowNum) -> new HeldSeat(row.getString("seat_id"), row.getLong("price_cents")))
                .list();
        List<String> seatIds = seats.stream().map(HeldSeat::seatId).toList();
        long totalPriceCents = seats.stream().mapToLong(HeldSeat::priceCents).sum();

        return jdbc.sql("SELECT id, event_id, user_id, status, expires_at FROM holds WHERE event_id = :eventId AND id = :holdId")
                .param("eventId", eventId)
                .param("holdId", holdId)
                .query((row, rowNum) -> new Hold(
                        row.getObject("id", UUID.class),
                        row.getObject("event_id", UUID.class),
                        row.getString("user_id"),
                        row.getString("status"),
                        seatIds,
                        totalPriceCents,
                        row.getObject("expires_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    private record HeldSeat(String seatId, long priceCents) {
    }

    @Transactional
    public void confirmHold(UUID eventId, UUID holdId) {
        int confirmed = jdbc.sql("""
                        UPDATE holds SET status = 'CONFIRMED'
                        WHERE event_id = :eventId AND id = :holdId AND status = 'ACTIVE' AND expires_at > now()
                        """)
                .param("eventId", eventId)
                .param("holdId", holdId)
                .update();

        if (confirmed == 0) {
            String status = findStatus(eventId, holdId)
                    .orElseThrow(() -> new HoldStateException(holdId, Problem.NOT_FOUND));
            if (status.equals("CONFIRMED")) {
                return;
            }
            boolean ranOutOfTime = status.equals("ACTIVE") || status.equals("EXPIRED");
            throw new HoldStateException(holdId, ranOutOfTime ? Problem.EXPIRED : Problem.NOT_ACTIVE);
        }

        jdbc.sql("""
                        UPDATE seats SET status = 'SOLD', version = version + 1
                        WHERE event_id = :eventId AND hold_id = :holdId AND status = 'HELD'
                        """)
                .param("eventId", eventId)
                .param("holdId", holdId)
                .update();
    }

    @Transactional
    public void releaseHold(UUID eventId, UUID holdId) {
        int released = jdbc.sql("""
                        UPDATE holds SET status = 'RELEASED'
                        WHERE event_id = :eventId AND id = :holdId AND status = 'ACTIVE'
                        """)
                .param("eventId", eventId)
                .param("holdId", holdId)
                .update();

        if (released == 0) {
            String status = findStatus(eventId, holdId)
                    .orElseThrow(() -> new HoldStateException(holdId, Problem.NOT_FOUND));
            if (status.equals("RELEASED") || status.equals("EXPIRED")) {
                return;
            }
            throw new HoldStateException(holdId, Problem.CONFIRMED);
        }

        jdbc.sql("""
                        UPDATE seats SET status = 'AVAILABLE', hold_id = NULL, version = version + 1
                        WHERE event_id = :eventId AND hold_id = :holdId AND status = 'HELD'
                        """)
                .param("eventId", eventId)
                .param("holdId", holdId)
                .update();
    }

    private Optional<String> findStatus(UUID eventId, UUID holdId) {
        return jdbc.sql("SELECT status FROM holds WHERE event_id = :eventId AND id = :holdId")
                .param("eventId", eventId)
                .param("holdId", holdId)
                .query(String.class)
                .optional();
    }

    private void requireValidSeatList(List<String> seatIds) {
        if (seatIds == null || seatIds.isEmpty()) {
            throw new IllegalArgumentException("At least one seat is required");
        }
        if (seatIds.size() > properties.maxSeatsPerHold()) {
            throw new IllegalArgumentException("At most " + properties.maxSeatsPerHold() + " seats per hold");
        }
        if (new HashSet<>(seatIds).size() != seatIds.size()) {
            throw new IllegalArgumentException("The same seat was requested twice");
        }
    }
}
