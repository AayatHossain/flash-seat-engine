package com.flashseat.order.api;

import com.flashseat.order.TestcontainersConfiguration;
import com.flashseat.order.inventory.InventoryException;
import com.flashseat.order.inventory.InventoryHold;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, OrderApiTest.FakeInventory.class})
class OrderApiTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeInventory {
        @Bean
        @Primary
        FakeInventoryClient fakeInventoryClient() {
            return new FakeInventoryClient();
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private FakeInventoryClient inventory;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void createsAnOrderAndItsEventTogether() throws Exception {
        InventoryHold hold = activeHold("alice");

        createOrder(hold, "key-1")
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.status").value("HELD"))
                .andExpect(jsonPath("$.amountCents").value(10_000))
                .andExpect(jsonPath("$.seatIds[0]").value("A-1"));

        UUID orderId = orderIdForHold(hold.holdId());
        String eventType = jdbc.sql("SELECT event_type FROM outbox WHERE aggregate_id = :orderId")
                .param("orderId", orderId.toString())
                .query(String.class)
                .single();
        assertThat(eventType).isEqualTo("OrderCreated");
    }

    @Test
    void retryReturnsTheSameOrder() throws Exception {
        InventoryHold hold = activeHold("bob");
        String first = createOrder(hold, "key-1").andReturn().getResponse().getContentAsString();

        String retry = createOrder(hold, "key-1")
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse().getContentAsString();

        assertThat(retry).isEqualTo(first);
        assertThat(ordersForHold(hold.holdId())).isEqualTo(1);
    }

    @Test
    void sameKeyForADifferentRequestIsRejected() throws Exception {
        InventoryHold firstHold = activeHold("carol");
        InventoryHold secondHold = new InventoryHold(UUID.randomUUID(), firstHold.eventId(), firstHold.userId(), "ACTIVE",
                List.of("B-1"), 5_000, Instant.now().plusSeconds(600));
        inventory.add(secondHold);
        createOrder(firstHold, "key-1").andExpect(status().isCreated());

        createOrder(secondHold, "key-1")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("idempotency-key-reused"));
        assertThat(ordersForHold(secondHold.holdId())).isZero();
    }

    @Test
    void twoBuyersCanUseTheSameKey() throws Exception {
        createOrder(activeHold("dave"), "same-key").andExpect(status().isCreated());
        createOrder(activeHold("erin"), "same-key").andExpect(status().isCreated());
    }

    @Test
    void secondOrderForTheSameHoldIsRefused() throws Exception {
        InventoryHold hold = activeHold("frank");
        createOrder(hold, "key-1").andExpect(status().isCreated());

        createOrder(hold, "key-2")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("order-already-exists"));
    }

    @Test
    void holdThatIsNoLongerActiveCannotBeOrdered() throws Exception {
        InventoryHold expired = new InventoryHold(UUID.randomUUID(), UUID.randomUUID(), "gina", "EXPIRED",
                List.of(), 0, Instant.now().minusSeconds(1));
        inventory.add(expired);

        createOrder(expired, "key-1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("hold-not-active"));
        assertThat(ordersForHold(expired.holdId())).isZero();
    }

    @Test
    void inventoryProblemsAreReportedClearly() throws Exception {
        InventoryHold unknown = new InventoryHold(UUID.randomUUID(), UUID.randomUUID(), "x", "ACTIVE", List.of(), 0, Instant.now());
        createOrder(unknown, "key-1")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("hold-not-found"));

        InventoryHold down = activeHold("henry");
        inventory.failWith(down.holdId(), new InventoryException.Unavailable("boom", null));
        createOrder(down, "key-1")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("inventory-unavailable"));
    }

    @Test
    void requestsMissingPiecesAreRejected() throws Exception {
        InventoryHold hold = activeHold("ivy");

        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON)
                        .header("Admission-Token", "token")
                        .content(body(hold)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid-request"));
        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "key-1")
                        .content(body(hold)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON)
                        .header("Admission-Token", "token")
                        .header("Idempotency-Key", "key-1")
                        .content("{ not json"))
                .andExpect(status().isBadRequest());
    }

    private InventoryHold activeHold(String userId) {
        InventoryHold hold = new InventoryHold(UUID.randomUUID(), UUID.randomUUID(), userId + "-" + UUID.randomUUID(),
                "ACTIVE", List.of("A-1", "A-2"), 10_000, Instant.now().plusSeconds(600));
        inventory.add(hold);
        return hold;
    }

    private ResultActions createOrder(InventoryHold hold, String idempotencyKey) throws Exception {
        return mvc.perform(post("/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Admission-Token", "token-for-" + hold.userId())
                .header("Idempotency-Key", idempotencyKey)
                .content(body(hold)));
    }

    private static String body(InventoryHold hold) {
        return "{\"eventId\":\"" + hold.eventId() + "\",\"holdId\":\"" + hold.holdId() + "\"}";
    }

    private UUID orderIdForHold(UUID holdId) {
        return jdbc.sql("SELECT id FROM orders WHERE hold_id = :holdId")
                .param("holdId", holdId)
                .query(UUID.class)
                .single();
    }

    private int ordersForHold(UUID holdId) {
        return jdbc.sql("SELECT count(*) FROM orders WHERE hold_id = :holdId")
                .param("holdId", holdId)
                .query(Integer.class)
                .single();
    }
}
