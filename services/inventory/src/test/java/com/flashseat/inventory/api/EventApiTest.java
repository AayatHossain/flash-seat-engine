package com.flashseat.inventory.api;

import com.flashseat.inventory.TestcontainersConfiguration;
import com.flashseat.inventory.hold.HoldService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class EventApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private HoldService holds;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void eventPageShowsSeatsWithoutRevealingWhoHasThem() throws Exception {
        UUID eventId = eventWithSeats("A-1", "A-2", "A-3");
        holds.placeHold(eventId, "someone", List.of("A-2"));

        mvc.perform(get("/events/{eventId}", eventId))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("max-age=2")))
                .andExpect(jsonPath("$.name").value("Test Concert"))
                .andExpect(jsonPath("$.availableSeats").value(2))
                .andExpect(jsonPath("$.seats[0].status").value("AVAILABLE"))
                .andExpect(jsonPath("$.seats[1].status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.seats[1].userId").doesNotExist());
    }

    @Test
    void unknownEventIsNotFound() throws Exception {
        mvc.perform(get("/events/{eventId}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("event-not-found"));
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
