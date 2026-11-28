package com.flashseat.inventory.catalog;

import com.flashseat.inventory.TestcontainersConfiguration;
import com.flashseat.inventory.admission.AdmissionTokenService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests cannot run real streaming replication, so with no replica configured the "replica" pool points at
 * the test database. What matters here is which pool each kind of request uses.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ReadReplicaRoutingTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AdmissionTokenService admissions;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private MeterRegistry metrics;

    @Test
    void eventPagesReadFromTheReplicaAndHoldsNeverDo() throws Exception {
        UUID eventId = eventWithSeats("A-1");

        double before = replicaConnectionsUsed();
        mvc.perform(get("/events/{eventId}", eventId)).andExpect(status().isOk());
        double afterPageView = replicaConnectionsUsed();

        mvc.perform(post("/events/{eventId}/holds", eventId)
                        .header("Admission-Token", admissions.issue("replica-buyer", eventId.toString()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seatIds\":[\"A-1\"]}"))
                .andExpect(status().isCreated());

        assertThat(afterPageView).isGreaterThan(before);
        assertThat(replicaConnectionsUsed()).isEqualTo(afterPageView);
    }

    @Test
    void lagIsZeroWhenTheDatabaseIsNotAReplica() {
        assertThat(metrics.get("flashseat.replica.lag").gauge().value()).isZero();
    }

    private double replicaConnectionsUsed() {
        return metrics.get("hikaricp.connections.usage").tag("pool", "replica").timer().count();
    }

    private UUID eventWithSeats(String... seatIds) {
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
}
