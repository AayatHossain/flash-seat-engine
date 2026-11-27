package com.flashseat.inventory.api;

import com.flashseat.inventory.TestcontainersConfiguration;
import com.flashseat.inventory.admission.AdmissionTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
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

/** What Prometheus sees when it scrapes a replica. */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureMetrics
@Import(TestcontainersConfiguration.class)
class MetricsApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AdmissionTokenService admissions;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void scrapeShowsBusinessCountersAndTheHoldLatencySloBucket() throws Exception {
        UUID eventId = eventWithSeats("A-1", "A-2");
        mvc.perform(post("/events/{eventId}/holds", eventId)
                        .header(HoldController.ADMISSION_HEADER, admissions.issue("metrics-buyer", eventId.toString()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seatIds\":[\"A-1\",\"A-2\"]}"))
                .andExpect(status().isCreated());

        String scrape = mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(scrape)
                .contains("flashseat_holds_placed_total{application=\"inventory\"}")
                .contains("flashseat_seats_held_total{application=\"inventory\"}");
        assertThat(scrape.lines())
                .filteredOn(line -> line.startsWith("http_server_requests_seconds_bucket{"))
                .anyMatch(line -> line.contains("le=\"0.2\"") && line.contains("uri=\"/events/{eventId}/holds\""));
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
