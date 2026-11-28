package com.flashseat.inventory.catalog;

import com.flashseat.inventory.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.AbstractDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class EventCatalogFallbackTest {

    @Autowired
    private JdbcClient primary;

    @Test
    void deadReplicaFallsBackToThePrimaryAndIsSkippedForAWhile() {
        UUID eventId = eventWithOneSeat();
        DeadReplica deadReplica = new DeadReplica();
        MovableClock clock = new MovableClock();
        EventCatalog catalog = new EventCatalog(JdbcClient.create(deadReplica), primary, clock);

        assertThat(catalog.loadEventPage(eventId)).isPresent();
        assertThat(catalog.loadEventPage(eventId)).isPresent();
        assertThat(deadReplica.attempts()).isEqualTo(1);

        clock.advance(EventCatalog.REPLICA_RETRY_AFTER.plusSeconds(1));
        assertThat(catalog.loadEventPage(eventId)).isPresent();
        assertThat(deadReplica.attempts()).isEqualTo(2);
    }

    private UUID eventWithOneSeat() {
        UUID eventId = UUID.randomUUID();
        primary.sql("""
                        INSERT INTO events (id, name, venue, starts_at, sale_opens_at)
                        VALUES (:id, 'Test Concert', 'Test Arena', :startsAt, :saleOpensAt)
                        """)
                .param("id", eventId)
                .param("startsAt", Instant.parse("2027-01-10T19:00:00Z").atOffset(ZoneOffset.UTC))
                .param("saleOpensAt", Instant.parse("2026-12-01T10:00:00Z").atOffset(ZoneOffset.UTC))
                .update();
        primary.sql("INSERT INTO seats (event_id, seat_id, section, price_cents) VALUES (:eventId, 'A-1', 'A', 5000)")
                .param("eventId", eventId)
                .update();
        return eventId;
    }

    /** A replica that is down: every connection attempt fails, and attempts are counted. */
    private static final class DeadReplica extends AbstractDataSource {
        private final AtomicInteger attempts = new AtomicInteger();

        @Override
        public Connection getConnection() throws SQLException {
            attempts.incrementAndGet();
            throw new SQLException("replica is down");
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
        }

        int attempts() {
            return attempts.get();
        }
    }

    private static final class MovableClock implements InstantSource {
        private Instant now = Instant.parse("2026-12-01T10:00:00Z");

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }
    }
}
