package com.flashseat.inventory.hold;

import com.flashseat.inventory.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** The core promise of the system: no matter how many buyers race, a seat is never sold twice. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class HoldConcurrencyTest {

    @Autowired
    private HoldService holds;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void twoHundredBuyersRaceForOneSeatAndExactlyOneWins() throws Exception {
        SeatFixtures fixtures = new SeatFixtures(jdbc);
        UUID eventId = fixtures.eventWithSeats("A-1");

        List<Outcome> outcomes = raceAll(200, buyer -> holds.placeHold(eventId, "buyer-" + buyer, List.of("A-1")));

        List<Outcome> winners = outcomes.stream().filter(Outcome::won).toList();
        assertThat(winners).hasSize(1);
        assertThat(outcomes).allMatch(o -> o.won() || o.error() instanceof SeatsUnavailableException);
        assertThat(fixtures.seatHoldId(eventId, "A-1")).isEqualTo(winners.getFirst().hold().id());
        assertThat(fixtures.holdCount(eventId)).isEqualTo(1);
    }

    @Test
    void buyersAskingForOverlappingSeatsInDifferentOrdersNeverDeadlock() throws Exception {
        SeatFixtures fixtures = new SeatFixtures(jdbc);
        UUID eventId = fixtures.eventWithSeats("A-1", "A-2", "A-3");
        List<List<String>> requests = List.of(
                List.of("A-1", "A-2"), List.of("A-2", "A-1"),
                List.of("A-2", "A-3"), List.of("A-3", "A-2"),
                List.of("A-3", "A-1"), List.of("A-1", "A-3"));

        List<Outcome> outcomes = raceAll(120, buyer ->
                holds.placeHold(eventId, "buyer-" + buyer, requests.get(buyer % requests.size())));

        // Any other exception (for example a deadlock) would fail this check.
        assertThat(outcomes).allMatch(o -> o.won() || o.error() instanceof SeatsUnavailableException);

        List<Hold> winners = outcomes.stream().filter(Outcome::won).map(Outcome::hold).toList();
        Set<String> seatsTaken = new HashSet<>();
        for (Hold winner : winners) {
            for (String seat : winner.seatIds()) {
                assertThat(seatsTaken.add(seat)).as("seat %s given to two buyers", seat).isTrue();
                assertThat(fixtures.seatHoldId(eventId, seat)).isEqualTo(winner.id());
            }
        }
        assertThat(winners).isNotEmpty();
    }

    private interface Purchase {
        Hold attempt(int buyer);
    }

    private record Outcome(Hold hold, Exception error) {
        boolean won() {
            return hold != null;
        }
    }

    /** Starts every buyer at the same moment to make the race as tight as possible. */
    private static List<Outcome> raceAll(int buyers, Purchase purchase) throws Exception {
        CountDownLatch startSignal = new CountDownLatch(1);
        List<Future<Outcome>> futures = new ArrayList<>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int buyer = 0; buyer < buyers; buyer++) {
                int id = buyer;
                futures.add(executor.submit(() -> {
                    startSignal.await();
                    try {
                        return new Outcome(purchase.attempt(id), null);
                    } catch (Exception e) {
                        return new Outcome(null, e);
                    }
                }));
            }
            startSignal.countDown();
        }

        List<Outcome> outcomes = new ArrayList<>();
        for (Future<Outcome> future : futures) {
            outcomes.add(future.get());
        }
        return outcomes;
    }
}
