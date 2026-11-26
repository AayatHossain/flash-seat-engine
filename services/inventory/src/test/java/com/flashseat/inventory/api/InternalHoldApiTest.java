package com.flashseat.inventory.api;

import com.flashseat.inventory.TestcontainersConfiguration;
import com.flashseat.inventory.hold.Hold;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class InternalHoldApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private HoldService holds;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void confirmSellsTheSeatsAndIsSafeToRepeat() throws Exception {
        UUID eventId = eventWithSeat("A-1");
        Hold hold = holds.placeHold(eventId, "alice", List.of("A-1"));

        mvc.perform(post(confirmUrl(hold))).andExpect(status().isNoContent());
        mvc.perform(post(confirmUrl(hold))).andExpect(status().isNoContent());

        assertThat(seatStatus(eventId)).isEqualTo("SOLD");
    }

    @Test
    void expiredHoldCannotBeConfirmed() throws Exception {
        UUID eventId = eventWithSeat("A-1");
        Hold hold = holds.placeHold(eventId, "alice", List.of("A-1"));
        jdbc.sql("UPDATE holds SET expires_at = now() - interval '1 second' WHERE id = :id").param("id", hold.id()).update();

        mvc.perform(post(confirmUrl(hold)))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("hold-expired"));
    }

    @Test
    void releaseFreesTheSeatsAndIsSafeToRepeat() throws Exception {
        UUID eventId = eventWithSeat("A-1");
        Hold hold = holds.placeHold(eventId, "alice", List.of("A-1"));

        mvc.perform(post(releaseUrl(hold))).andExpect(status().isNoContent());
        mvc.perform(post(releaseUrl(hold))).andExpect(status().isNoContent());

        assertThat(seatStatus(eventId)).isEqualTo("AVAILABLE");
    }

    @Test
    void soldSeatsCannotBeReleased() throws Exception {
        UUID eventId = eventWithSeat("A-1");
        Hold hold = holds.placeHold(eventId, "alice", List.of("A-1"));
        holds.confirmHold(eventId, hold.id());

        mvc.perform(post(releaseUrl(hold)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("hold-not-active"));
    }

    @Test
    void unknownHoldIsNotFound() throws Exception {
        mvc.perform(post("/internal/events/{eventId}/holds/{holdId}/confirm", UUID.randomUUID(), UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    private static String confirmUrl(Hold hold) {
        return "/internal/events/" + hold.eventId() + "/holds/" + hold.id() + "/confirm";
    }

    private static String releaseUrl(Hold hold) {
        return "/internal/events/" + hold.eventId() + "/holds/" + hold.id() + "/release";
    }

    private String seatStatus(UUID eventId) {
        return jdbc.sql("SELECT status FROM seats WHERE event_id = :eventId AND seat_id = 'A-1'")
                .param("eventId", eventId)
                .query(String.class)
                .single();
    }

    private UUID eventWithSeat(String seatId) {
        UUID eventId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO events (id, name, venue, starts_at, sale_opens_at)
                        VALUES (:id, 'Test Concert', 'Test Arena', :startsAt, :saleOpensAt)
                        """)
                .param("id", eventId)
                .param("startsAt", Instant.parse("2027-01-10T19:00:00Z").atOffset(ZoneOffset.UTC))
                .param("saleOpensAt", Instant.parse("2026-12-01T10:00:00Z").atOffset(ZoneOffset.UTC))
                .update();
        jdbc.sql("INSERT INTO seats (event_id, seat_id, section, price_cents) VALUES (:eventId, :seatId, 'A', 5000)")
                .param("eventId", eventId)
                .param("seatId", seatId)
                .update();
        return eventId;
    }
}
