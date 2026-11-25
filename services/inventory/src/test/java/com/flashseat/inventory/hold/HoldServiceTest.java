package com.flashseat.inventory.hold;

import com.flashseat.inventory.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class HoldServiceTest {

    @Autowired
    private HoldService holds;

    @Autowired
    private JdbcClient jdbc;

    private SeatFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new SeatFixtures(jdbc);
    }

    @Test
    void placingAHoldClaimsEveryRequestedSeat() {
        UUID eventId = fixtures.eventWithSeats("A-1", "A-2", "A-3");

        Hold hold = holds.placeHold(eventId, "alice", List.of("A-1", "A-2"));

        assertThat(fixtures.seatsHeldBy(eventId, hold.id())).containsExactly("A-1", "A-2");
        assertThat(fixtures.seatStatus(eventId, "A-3")).isEqualTo("AVAILABLE");
        assertThat(fixtures.holdStatus(eventId, hold.id())).isEqualTo("ACTIVE");
        assertThat(hold.expiresAt()).isBetween(Instant.now().plus(Duration.ofMinutes(9)), Instant.now().plus(Duration.ofMinutes(11)));
    }

    @Test
    void holdIsAllOrNothing() {
        UUID eventId = fixtures.eventWithSeats("A-1", "A-2");
        holds.placeHold(eventId, "bob", List.of("A-2"));

        assertThatThrownBy(() -> holds.placeHold(eventId, "alice", List.of("A-1", "A-2")))
                .isInstanceOf(SeatsUnavailableException.class);

        assertThat(fixtures.seatStatus(eventId, "A-1")).isEqualTo("AVAILABLE");
        assertThat(fixtures.holdCount(eventId)).isEqualTo(1);
    }

    @Test
    void unknownSeatMakesTheWholeHoldFail() {
        UUID eventId = fixtures.eventWithSeats("A-1");

        assertThatThrownBy(() -> holds.placeHold(eventId, "alice", List.of("A-1", "Z-99")))
                .isInstanceOf(SeatsUnavailableException.class);
        assertThat(fixtures.seatStatus(eventId, "A-1")).isEqualTo("AVAILABLE");
    }

    @Test
    void rejectsBadSeatLists() {
        UUID eventId = fixtures.eventWithSeats("A-1");

        assertThatThrownBy(() -> holds.placeHold(eventId, "alice", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> holds.placeHold(eventId, "alice", List.of("A-1", "A-1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> holds.placeHold(eventId, "alice", List.of("1", "2", "3", "4", "5", "6", "7", "8", "9")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void confirmingAHoldSellsItsSeats() {
        UUID eventId = fixtures.eventWithSeats("A-1", "A-2");
        Hold hold = holds.placeHold(eventId, "alice", List.of("A-1", "A-2"));

        holds.confirmHold(eventId, hold.id());

        assertThat(fixtures.seatStatus(eventId, "A-1")).isEqualTo("SOLD");
        assertThat(fixtures.seatStatus(eventId, "A-2")).isEqualTo("SOLD");
        assertThat(fixtures.holdStatus(eventId, hold.id())).isEqualTo("CONFIRMED");
    }

    @Test
    void confirmingTwiceIsHarmless() {
        UUID eventId = fixtures.eventWithSeats("A-1");
        Hold hold = holds.placeHold(eventId, "alice", List.of("A-1"));

        holds.confirmHold(eventId, hold.id());
        holds.confirmHold(eventId, hold.id());

        assertThat(fixtures.seatStatus(eventId, "A-1")).isEqualTo("SOLD");
    }

    @Test
    void expiredHoldCannotBeConfirmed() {
        UUID eventId = fixtures.eventWithSeats("A-1");
        Hold hold = holds.placeHold(eventId, "alice", List.of("A-1"));
        fixtures.expireNow(eventId, hold.id());

        assertThatThrownBy(() -> holds.confirmHold(eventId, hold.id()))
                .isInstanceOf(HoldStateException.class)
                .hasMessageContaining("expired");
        assertThat(fixtures.seatStatus(eventId, "A-1")).isEqualTo("HELD");
    }

    @Test
    void releasingAHoldFreesItsSeatsForOtherBuyers() {
        UUID eventId = fixtures.eventWithSeats("A-1");
        Hold hold = holds.placeHold(eventId, "alice", List.of("A-1"));

        holds.releaseHold(eventId, hold.id());
        holds.releaseHold(eventId, hold.id());

        assertThat(fixtures.seatStatus(eventId, "A-1")).isEqualTo("AVAILABLE");
        assertThat(fixtures.seatHoldId(eventId, "A-1")).isNull();
        Hold next = holds.placeHold(eventId, "bob", List.of("A-1"));
        assertThat(fixtures.seatHoldId(eventId, "A-1")).isEqualTo(next.id());
    }

    @Test
    void soldSeatsCannotBeReleased() {
        UUID eventId = fixtures.eventWithSeats("A-1");
        Hold hold = holds.placeHold(eventId, "alice", List.of("A-1"));
        holds.confirmHold(eventId, hold.id());

        assertThatThrownBy(() -> holds.releaseHold(eventId, hold.id()))
                .isInstanceOf(HoldStateException.class);
        assertThat(fixtures.seatStatus(eventId, "A-1")).isEqualTo("SOLD");
    }

    @Test
    void unknownHoldIsReported() {
        UUID eventId = fixtures.eventWithSeats("A-1");

        assertThatThrownBy(() -> holds.confirmHold(eventId, UUID.randomUUID()))
                .isInstanceOf(HoldStateException.class)
                .hasMessageContaining("does not exist");
    }
}
