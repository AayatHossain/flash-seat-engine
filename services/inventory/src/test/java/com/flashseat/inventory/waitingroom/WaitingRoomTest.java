package com.flashseat.inventory.waitingroom;

import com.flashseat.inventory.TestcontainersConfiguration;
import com.flashseat.inventory.admission.AdmissionCheck;
import com.flashseat.inventory.admission.AdmissionTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs against real Redis. Tests use admit-per-second = 2 (see test config). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class WaitingRoomTest {

    @Autowired
    private WaitingRoom waitingRoom;

    @Autowired
    private AdmissionTokenService admissions;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void buyersGetPositionsInArrivalOrderAndKeepThemWhenJoiningAgain() {
        UUID eventId = eventOpening(Instant.now().plus(Duration.ofHours(1)));

        QueueStatus alice = waitingRoom.join(eventId, "alice");
        QueueStatus bob = waitingRoom.join(eventId, "bob");
        QueueStatus aliceAgain = waitingRoom.join(eventId, "alice");

        assertThat(alice.position()).isEqualTo(1);
        assertThat(bob.position()).isEqualTo(2);
        assertThat(aliceAgain.position()).isEqualTo(1);
    }

    @Test
    void twoThousandBuyersJoiningAtOnceGetUniquePositionsWithNoGaps() throws Exception {
        UUID eventId = eventOpening(Instant.now().plus(Duration.ofHours(1)));

        List<Future<Long>> joins = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 2000; i++) {
                String buyer = "buyer-" + i;
                joins.add(executor.submit(() -> waitingRoom.join(eventId, buyer).position()));
            }
        }

        Set<Long> positions = new HashSet<>();
        for (Future<Long> join : joins) {
            positions.add(join.get());
        }
        assertThat(positions).hasSize(2000);
        assertThat(positions).allMatch(position -> position >= 1 && position <= 2000);
    }

    @Test
    void sameBuyerClickingJoinManyTimesAtOnceGetsOnePosition() throws Exception {
        UUID eventId = eventOpening(Instant.now().plus(Duration.ofHours(1)));

        List<Future<Long>> clicks = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 50; i++) {
                clicks.add(executor.submit(() -> waitingRoom.join(eventId, "impatient").position()));
            }
        }
        Set<Long> positions = new HashSet<>();
        for (Future<Long> click : clicks) {
            positions.add(click.get());
        }

        assertThat(positions).containsExactly(1L);
        assertThat(waitingRoom.join(eventId, "next-buyer").position()).isEqualTo(2);
    }

    @Test
    void nobodyIsAdmittedBeforeTheSaleOpens() {
        UUID eventId = eventOpening(Instant.now().plus(Duration.ofMinutes(10)));
        waitingRoom.join(eventId, "early-1");

        QueueStatus second = waitingRoom.join(eventId, "early-2");

        assertThat(second.admitted()).isFalse();
        assertThat(second.peopleAhead()).isEqualTo(1);
        assertThat(second.admissionToken()).isNull();
        assertThat(second.estimatedWaitSeconds()).isGreaterThanOrEqualTo(Duration.ofMinutes(9).toSeconds());
    }

    @Test
    void admittedBuyerReceivesATokenThatWorksForThisEvent() {
        UUID eventId = eventOpening(Instant.now().minus(Duration.ofMinutes(5)));

        QueueStatus status = waitingRoom.join(eventId, "lucky");

        assertThat(status.admitted()).isTrue();
        assertThat(admissions.verify(status.admissionToken(), eventId.toString()))
                .isInstanceOfSatisfying(AdmissionCheck.Admitted.class,
                        admitted -> assertThat(admitted.token().userId()).isEqualTo("lucky"));
    }

    @Test
    void gateOpensGraduallyAtTheConfiguredRate() {
        // Opened 10 seconds ago at 2 per second: about the first 20 positions are in.
        UUID eventId = eventOpening(Instant.now().minus(Duration.ofSeconds(10)));
        List<QueueStatus> queue = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            queue.add(waitingRoom.join(eventId, "buyer-" + i));
        }

        assertThat(queue.get(0).admitted()).isTrue();
        assertThat(queue.get(59).admitted()).isFalse();
        assertThat(queue.get(59).estimatedWaitSeconds()).isBetween(15L, 25L);
        assertThat(queue.stream().filter(QueueStatus::admitted).count()).isBetween(19L, 24L);
    }

    @Test
    void statusRequiresHavingJoinedAndAnExistingEvent() {
        UUID eventId = eventOpening(Instant.now());

        assertThatThrownBy(() -> waitingRoom.status(eventId, "stranger"))
                .isInstanceOf(WaitingRoomException.NotInQueue.class);
        assertThatThrownBy(() -> waitingRoom.join(UUID.randomUUID(), "someone"))
                .isInstanceOf(WaitingRoomException.EventNotFound.class);
    }

    private UUID eventOpening(Instant saleOpensAt) {
        UUID eventId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO events (id, name, venue, starts_at, sale_opens_at)
                        VALUES (:id, 'Test Concert', 'Test Arena', :startsAt, :saleOpensAt)
                        """)
                .param("id", eventId)
                .param("startsAt", saleOpensAt.plus(Duration.ofDays(30)).atOffset(ZoneOffset.UTC))
                .param("saleOpensAt", saleOpensAt.atOffset(ZoneOffset.UTC))
                .update();
        return eventId;
    }
}
