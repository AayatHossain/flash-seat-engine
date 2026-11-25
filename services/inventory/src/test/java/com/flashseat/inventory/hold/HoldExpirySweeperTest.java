package com.flashseat.inventory.hold;

import com.flashseat.inventory.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class HoldExpirySweeperTest {

    @Autowired
    private HoldExpirySweeper sweeper;

    @Autowired
    private HoldService holds;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private SeatFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new SeatFixtures(jdbc);
    }

    @Test
    void expiredHoldsGiveTheirSeatsBack() {
        UUID eventId = fixtures.eventWithSeats("A-1", "A-2", "A-3");
        Hold expired = holds.placeHold(eventId, "alice", List.of("A-1", "A-2"));
        Hold active = holds.placeHold(eventId, "bob", List.of("A-3"));
        fixtures.expireNow(eventId, expired.id());

        sweeper.sweepExpiredHolds();

        assertThat(fixtures.holdStatus(eventId, expired.id())).isEqualTo("EXPIRED");
        assertThat(fixtures.seatStatus(eventId, "A-1")).isEqualTo("AVAILABLE");
        assertThat(fixtures.seatStatus(eventId, "A-2")).isEqualTo("AVAILABLE");
        assertThat(fixtures.holdStatus(eventId, active.id())).isEqualTo("ACTIVE");
        assertThat(fixtures.seatHoldId(eventId, "A-3")).isEqualTo(active.id());
    }

    @Test
    void sweptSeatsCanBeBoughtAgainButTheOldHoldCannotBeConfirmed() {
        UUID eventId = fixtures.eventWithSeats("A-1");
        Hold old = holds.placeHold(eventId, "alice", List.of("A-1"));
        fixtures.expireNow(eventId, old.id());
        sweeper.sweepExpiredHolds();

        Hold fresh = holds.placeHold(eventId, "bob", List.of("A-1"));

        assertThat(fixtures.seatHoldId(eventId, "A-1")).isEqualTo(fresh.id());
        assertThatThrownBy(() -> holds.confirmHold(eventId, old.id()))
                .isInstanceOf(HoldStateException.class)
                .hasMessageContaining("has expired");
    }

    @Test
    void confirmedHoldsAreNeverSwept() {
        UUID eventId = fixtures.eventWithSeats("A-1");
        Hold hold = holds.placeHold(eventId, "alice", List.of("A-1"));
        holds.confirmHold(eventId, hold.id());
        fixtures.expireNow(eventId, hold.id());

        sweeper.sweepExpiredHolds();

        assertThat(fixtures.holdStatus(eventId, hold.id())).isEqualTo("CONFIRMED");
        assertThat(fixtures.seatStatus(eventId, "A-1")).isEqualTo("SOLD");
    }

    @Test
    void sweepsInBatchesUntilNothingIsLeft() {
        int holdCount = 35;
        UUID eventId = eventWithExpiredHolds(holdCount);

        sweeper.sweepExpiredHolds();

        assertEveryHoldExpiredExactlyOnce(eventId, holdCount);
    }

    @Test
    void onlyTheReplicaHoldingTheLockSweeps() throws Exception {
        UUID eventId = fixtures.eventWithSeats("A-1");
        Hold hold = holds.placeHold(eventId, "alice", List.of("A-1"));
        fixtures.expireNow(eventId, hold.id());

        CountDownLatch otherReplicaHasLock = new CountDownLatch(1);
        CountDownLatch otherReplicaMayFinish = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.sql("SELECT pg_advisory_xact_lock(7340001)").query().singleRow();
                otherReplicaHasLock.countDown();
                awaitQuietly(otherReplicaMayFinish);
            }));

            otherReplicaHasLock.await();
            int expiredWhileLocked = sweeper.sweepExpiredHolds();
            otherReplicaMayFinish.countDown();

            assertThat(expiredWhileLocked).isZero();
            assertThat(fixtures.holdStatus(eventId, hold.id())).isEqualTo("ACTIVE");
        }

        sweeper.sweepExpiredHolds();
        assertThat(fixtures.holdStatus(eventId, hold.id())).isEqualTo("EXPIRED");
    }

    @Test
    void manyReplicasSweepingAtOnceProcessEachHoldExactlyOnce() throws Exception {
        int holdCount = 50;
        UUID eventId = eventWithExpiredHolds(holdCount);

        List<Future<Integer>> runs = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int replica = 0; replica < 8; replica++) {
                runs.add(executor.submit(sweeper::sweepExpiredHolds));
            }
        }
        for (Future<Integer> run : runs) {
            run.get();
        }
        // Some runs may have found the lock taken and done nothing; one last run picks up anything left.
        sweeper.sweepExpiredHolds();

        assertEveryHoldExpiredExactlyOnce(eventId, holdCount);
    }

    private UUID eventWithExpiredHolds(int count) {
        String[] seatIds = new String[count];
        for (int i = 0; i < count; i++) {
            seatIds[i] = "S-" + i;
        }
        UUID eventId = fixtures.eventWithSeats(seatIds);
        for (String seatId : seatIds) {
            Hold hold = holds.placeHold(eventId, "buyer-" + seatId, List.of(seatId));
            fixtures.expireNow(eventId, hold.id());
        }
        return eventId;
    }

    private void assertEveryHoldExpiredExactlyOnce(UUID eventId, int holdCount) {
        int expiredHolds = jdbc.sql("SELECT count(*) FROM holds WHERE event_id = :eventId AND status = 'EXPIRED'")
                .param("eventId", eventId)
                .query(Integer.class)
                .single();
        // Each seat's version went up once when it was held and once when it was freed.
        // A hold processed twice would push it higher.
        List<Long> seatVersions = jdbc.sql("SELECT version FROM seats WHERE event_id = :eventId AND status = 'AVAILABLE'")
                .param("eventId", eventId)
                .query(Long.class)
                .list();

        assertThat(expiredHolds).isEqualTo(holdCount);
        assertThat(seatVersions).hasSize(holdCount).containsOnly(2L);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
