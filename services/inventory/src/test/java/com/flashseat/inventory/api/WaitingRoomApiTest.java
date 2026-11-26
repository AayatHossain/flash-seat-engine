package com.flashseat.inventory.api;

import com.flashseat.inventory.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class WaitingRoomApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void buyerJoinsGetsAdmittedAndHoldsSeatsWithTheToken() throws Exception {
        UUID eventId = eventWithSeat(Instant.now().minus(Duration.ofMinutes(1)));
        String buyer = "buyer-" + UUID.randomUUID();

        String body = mvc.perform(post("/events/{eventId}/queue", eventId).header("User-Id", buyer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.position").value(1))
                .andExpect(jsonPath("$.admitted").value(true))
                .andReturn().getResponse().getContentAsString();
        String token = body.replaceAll(".*\"admissionToken\":\"([^\"]+)\".*", "$1");

        mvc.perform(post("/events/{eventId}/holds", eventId)
                        .header("Admission-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seatIds\":[\"A-1\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(buyer));
    }

    @Test
    void waitingBuyerCanCheckTheirPlace() throws Exception {
        UUID eventId = eventWithSeat(Instant.now().plus(Duration.ofHours(1)));
        mvc.perform(post("/events/{eventId}/queue", eventId).header("User-Id", "first"));
        mvc.perform(post("/events/{eventId}/queue", eventId).header("User-Id", "second"));

        mvc.perform(get("/events/{eventId}/queue/me", eventId).header("User-Id", "second"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.position").value(2))
                .andExpect(jsonPath("$.peopleAhead").value(1))
                .andExpect(jsonPath("$.admitted").value(false))
                .andExpect(jsonPath("$.admissionToken").doesNotExist());
    }

    @Test
    void errorsAreClear() throws Exception {
        UUID eventId = eventWithSeat(Instant.now());

        mvc.perform(get("/events/{eventId}/queue/me", eventId).header("User-Id", "nobody"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not-in-queue"));
        mvc.perform(post("/events/{eventId}/queue", UUID.randomUUID()).header("User-Id", "someone"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("event-not-found"));
        mvc.perform(post("/events/{eventId}/queue", eventId))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/events/{eventId}/queue", eventId).header("User-Id", "bad:id"))
                .andExpect(status().isBadRequest());
    }

    private UUID eventWithSeat(Instant saleOpensAt) {
        UUID eventId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO events (id, name, venue, starts_at, sale_opens_at)
                        VALUES (:id, 'Test Concert', 'Test Arena', :startsAt, :saleOpensAt)
                        """)
                .param("id", eventId)
                .param("startsAt", saleOpensAt.plus(Duration.ofDays(30)).atOffset(ZoneOffset.UTC))
                .param("saleOpensAt", saleOpensAt.atOffset(ZoneOffset.UTC))
                .update();
        jdbc.sql("INSERT INTO seats (event_id, seat_id, section, price_cents) VALUES (:eventId, 'A-1', 'A', 5000)")
                .param("eventId", eventId)
                .update();
        return eventId;
    }
}
