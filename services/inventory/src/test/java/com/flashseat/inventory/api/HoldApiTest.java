package com.flashseat.inventory.api;

import com.flashseat.inventory.TestcontainersConfiguration;
import com.flashseat.inventory.admission.AdmissionTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class HoldApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AdmissionTokenService admissions;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void admittedBuyerCanHoldSeats() throws Exception {
        UUID eventId = eventWithSeats("A-1", "A-2");

        placeHold(eventId, tokenFor("alice", eventId), "A-1", "A-2")
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/events/" + eventId + "/holds/")))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.seatIds[0]").value("A-1"))
                .andExpect(jsonPath("$.seatIds[1]").value("A-2"))
                .andExpect(jsonPath("$.totalPriceCents").value(10_000))
                .andExpect(jsonPath("$.expiresAt").exists());
    }

    @Test
    void takenSeatReturnsConflict() throws Exception {
        UUID eventId = eventWithSeats("A-1");
        placeHold(eventId, tokenFor("bob", eventId), "A-1").andExpect(status().isCreated());

        placeHold(eventId, tokenFor("alice", eventId), "A-1")
                .andExpect(status().isConflict())
                .andExpect(header().string("Content-Type", containsString("application/problem+json")))
                .andExpect(jsonPath("$.code").value("seats-unavailable"));
    }

    @Test
    void missingOrWrongAdmissionTokenIsForbidden() throws Exception {
        UUID eventId = eventWithSeats("A-1");
        UUID otherEvent = UUID.randomUUID();

        placeHold(eventId, null, "A-1")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("admission-required"));
        placeHold(eventId, tokenFor("alice", otherEvent), "A-1")
                .andExpect(status().isForbidden());
        placeHold(eventId, "forged.token", "A-1")
                .andExpect(status().isForbidden());
    }

    @Test
    void tooManyRequestsAreSlowedDown() throws Exception {
        UUID eventId = eventWithSeats("A-1", "A-2", "A-3", "A-4", "A-5", "A-6");
        String token = tokenFor("eager", eventId);

        // Test config allows a burst of 5 requests per buyer.
        for (String seat : new String[]{"A-1", "A-2", "A-3", "A-4", "A-5"}) {
            placeHold(eventId, token, seat).andExpect(status().isCreated());
        }
        placeHold(eventId, token, "A-6")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("rate-limited"));
    }

    @Test
    void badRequestsAreRejected() throws Exception {
        UUID eventId = eventWithSeats("A-1");

        placeHold(eventId, tokenFor("alice", eventId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid-request"));

        mvc.perform(post("/events/{eventId}/holds", eventId)
                        .header(HoldController.ADMISSION_HEADER, tokenFor("carol", eventId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ not json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void buyerCanSeeAndReleaseTheirOwnHold() throws Exception {
        UUID eventId = eventWithSeats("A-1");
        String token = tokenFor("dave", eventId);
        String location = placeHold(eventId, token, "A-1").andReturn().getResponse().getHeader("Location");

        mvc.perform(get(location).header(HoldController.ADMISSION_HEADER, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seatIds[0]").value("A-1"));

        mvc.perform(delete(location).header(HoldController.ADMISSION_HEADER, token))
                .andExpect(status().isNoContent());
        mvc.perform(delete(location).header(HoldController.ADMISSION_HEADER, token))
                .andExpect(status().isNoContent());

        placeHold(eventId, tokenFor("erin", eventId), "A-1").andExpect(status().isCreated());
    }

    @Test
    void someoneElsesHoldLooksLikeItDoesNotExist() throws Exception {
        UUID eventId = eventWithSeats("A-1");
        String location = placeHold(eventId, tokenFor("frank", eventId), "A-1")
                .andReturn().getResponse().getHeader("Location");
        String strangerToken = tokenFor("mallory", eventId);

        mvc.perform(get(location).header(HoldController.ADMISSION_HEADER, strangerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("hold-not-found"));
        mvc.perform(delete(location).header(HoldController.ADMISSION_HEADER, strangerToken))
                .andExpect(status().isNotFound());
    }

    private ResultActions placeHold(UUID eventId, String token, String... seatIds) throws Exception {
        var request = post("/events/{eventId}/holds", eventId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(seatsJson(seatIds));
        if (token != null) {
            request.header(HoldController.ADMISSION_HEADER, token);
        }
        return mvc.perform(request);
    }

    // Every token belongs to a brand-new buyer, so rate limits never leak between tests.
    private String tokenFor(String name, UUID eventId) {
        return admissions.issue(name + "-" + UUID.randomUUID(), eventId.toString());
    }

    private static String seatsJson(String... seatIds) {
        String quoted = Arrays.stream(seatIds).map(seat -> "\"" + seat + "\"").collect(Collectors.joining(","));
        return "{\"seatIds\":[" + quoted + "]}";
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
