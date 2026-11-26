package com.flashseat.order.inventory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RestInventoryClientTest {

    private static final UUID EVENT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID HOLD = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String HOLD_URL = "http://inventory/events/" + EVENT + "/holds/" + HOLD;

    private MockRestServiceServer server;
    private RestInventoryClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://inventory");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestInventoryClient(builder.build());
    }

    @Test
    void readsTheHoldAndForwardsTheAdmissionToken() {
        server.expect(requestTo(HOLD_URL))
                .andExpect(header("Admission-Token", "token-123"))
                .andRespond(withSuccess("""
                        {"holdId":"%s","eventId":"%s","userId":"alice","status":"ACTIVE",
                         "seatIds":["A-1","A-2"],"totalPriceCents":10000,"expiresAt":"2026-12-01T10:10:00Z"}
                        """.formatted(HOLD, EVENT), MediaType.APPLICATION_JSON));

        InventoryHold hold = client.getHold(EVENT, HOLD, "token-123");

        assertThat(hold).isEqualTo(new InventoryHold(HOLD, EVENT, "alice", "ACTIVE", List.of("A-1", "A-2"),
                10_000, Instant.parse("2026-12-01T10:10:00Z")));
        server.verify();
    }

    @Test
    void missingHoldBecomesHoldNotFound() {
        server.expect(requestTo(HOLD_URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.getHold(EVENT, HOLD, "token"))
                .isInstanceOf(InventoryException.HoldNotFound.class);
    }

    @Test
    void rejectedTokenBecomesAdmissionRejected() {
        server.expect(requestTo(HOLD_URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> client.getHold(EVENT, HOLD, "bad-token"))
                .isInstanceOf(InventoryException.AdmissionRejected.class);
    }

    @Test
    void rateLimitKeepsTheRetryAfterHint() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "7");
        server.expect(requestTo(HOLD_URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(headers));

        assertThatThrownBy(() -> client.getHold(EVENT, HOLD, "token"))
                .isInstanceOfSatisfying(InventoryException.RateLimited.class,
                        e -> assertThat(e.getRetryAfter()).isEqualTo(Duration.ofSeconds(7)));
    }

    @Test
    void confirmAnswersAreTranslated() {
        String confirmUrl = "http://inventory/internal/events/" + EVENT + "/holds/" + HOLD + "/confirm";
        server.expect(requestTo(confirmUrl)).andRespond(withStatus(HttpStatus.NO_CONTENT));
        server.expect(requestTo(confirmUrl)).andRespond(withStatus(HttpStatus.GONE));
        server.expect(requestTo(confirmUrl)).andRespond(withStatus(HttpStatus.CONFLICT));
        server.expect(requestTo(confirmUrl)).andRespond(withServerError());

        assertThat(client.confirmHold(EVENT, HOLD)).isEqualTo(InventoryClient.ConfirmResult.CONFIRMED);
        assertThat(client.confirmHold(EVENT, HOLD)).isEqualTo(InventoryClient.ConfirmResult.EXPIRED);
        assertThat(client.confirmHold(EVENT, HOLD)).isEqualTo(InventoryClient.ConfirmResult.NOT_ACTIVE);
        assertThatThrownBy(() -> client.confirmHold(EVENT, HOLD)).isInstanceOf(InventoryException.Unavailable.class);
    }

    @Test
    void serverErrorBecomesUnavailable() {
        server.expect(requestTo(HOLD_URL)).andRespond(withServerError());

        assertThatThrownBy(() -> client.getHold(EVENT, HOLD, "token"))
                .isInstanceOf(InventoryException.Unavailable.class);
    }
}
