package com.flashseat.order.api;

import com.flashseat.order.TestcontainersConfiguration;
import com.flashseat.order.api.CheckoutTestBeans.SwitchablePaymentGateway;
import com.flashseat.order.inventory.InventoryHold;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, CheckoutTestBeans.class})
class PaymentApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private FakeInventoryClient inventory;

    @Autowired
    private SwitchablePaymentGateway gateway;

    @Autowired
    private JdbcClient jdbc;

    @AfterEach
    void providerBackUp() {
        gateway.setDown(false);
    }

    @Test
    void payingAHeldOrderStartsAPayment() throws Exception {
        UUID orderId = heldOrder(activeHold("alice"));

        pay(orderId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAYMENT_PENDING"))
                .andExpect(jsonPath("$.paymentIntentId").value(startsWith("pi_")))
                .andExpect(jsonPath("$.clientSecret").exists())
                .andExpect(jsonPath("$.amountCents").value(10_000));

        assertThat(orderStatus(orderId)).isEqualTo("PAYMENT_PENDING");
        assertThat(eventsOf(orderId, "PaymentStarted")).isEqualTo(1);
    }

    @Test
    void payingTwiceReusesTheSamePayment() throws Exception {
        UUID orderId = heldOrder(activeHold("bob"));

        String first = paymentIntentId(pay(orderId));
        String second = paymentIntentId(pay(orderId));

        assertThat(second).isEqualTo(first);
        assertThat(paymentIntentStored(orderId)).isEqualTo(first);
        assertThat(eventsOf(orderId, "PaymentStarted")).isEqualTo(1);
    }

    @Test
    void manySimultaneousClicksCreateOnePayment() throws Exception {
        UUID orderId = heldOrder(activeHold("carol"));
        int paymentsBefore = gateway.paymentCount();

        List<Future<String>> clicks = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 20; i++) {
                clicks.add(executor.submit(() -> paymentIntentId(pay(orderId))));
            }
        }

        Set<String> paymentIds = new HashSet<>();
        for (Future<String> click : clicks) {
            paymentIds.add(click.get());
        }
        assertThat(paymentIds).hasSize(1);
        assertThat(gateway.paymentCount() - paymentsBefore).isEqualTo(1);
        assertThat(eventsOf(orderId, "PaymentStarted")).isEqualTo(1);
    }

    @Test
    void someoneElsesOrderLooksLikeItDoesNotExist() throws Exception {
        InventoryHold hold = activeHold("dave");
        UUID orderId = heldOrder(hold);
        inventory.add(new InventoryHold(hold.holdId(), hold.eventId(), "mallory", "ACTIVE", hold.seatIds(),
                hold.totalPriceCents(), hold.expiresAt()));

        pay(orderId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("order-not-found"));
        assertThat(orderStatus(orderId)).isEqualTo("HELD");
    }

    @Test
    void unknownOrderIsNotFound() throws Exception {
        pay(UUID.randomUUID()).andExpect(status().isNotFound());
    }

    @Test
    void paidOrderCannotBePaidAgain() throws Exception {
        UUID orderId = heldOrder(activeHold("erin"));
        jdbc.sql("UPDATE orders SET status = 'PAID' WHERE id = :id").param("id", orderId).update();

        pay(orderId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("order-not-payable"));
    }

    @Test
    void expiredSeatsCannotBePaidFor() throws Exception {
        InventoryHold hold = activeHold("frank");
        UUID orderId = heldOrder(hold);
        inventory.add(new InventoryHold(hold.holdId(), hold.eventId(), hold.userId(), "EXPIRED", List.of(), 0, hold.expiresAt()));

        pay(orderId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("hold-not-active"));
        assertThat(orderStatus(orderId)).isEqualTo("HELD");
    }

    @Test
    void providerOutageLeavesTheOrderUntouched() throws Exception {
        UUID orderId = heldOrder(activeHold("gina"));
        gateway.setDown(true);

        pay(orderId)
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("payment-provider-unavailable"));
        assertThat(orderStatus(orderId)).isEqualTo("HELD");

        gateway.setDown(false);
        pay(orderId).andExpect(status().isOk());
    }

    private ResultActions pay(UUID orderId) throws Exception {
        return mvc.perform(post("/orders/{orderId}/pay", orderId).header("Admission-Token", "token"));
    }

    private static String paymentIntentId(ResultActions result) throws Exception {
        String body = result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        int start = body.indexOf("\"paymentIntentId\":\"") + "\"paymentIntentId\":\"".length();
        return body.substring(start, body.indexOf('"', start));
    }

    private InventoryHold activeHold(String name) {
        InventoryHold hold = new InventoryHold(UUID.randomUUID(), UUID.randomUUID(), name + "-" + UUID.randomUUID(),
                "ACTIVE", List.of("A-1", "A-2"), 10_000, Instant.now().plusSeconds(600));
        inventory.add(hold);
        return hold;
    }

    private UUID heldOrder(InventoryHold hold) {
        UUID orderId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO orders (id, user_id, event_id, hold_id, seat_ids, status, amount_cents, currency)
                        VALUES (:id, :userId, :eventId, :holdId, :seatIds, 'HELD', :amount, 'USD')
                        """)
                .param("id", orderId)
                .param("userId", hold.userId())
                .param("eventId", hold.eventId())
                .param("holdId", hold.holdId())
                .param("seatIds", hold.seatIds().toArray(String[]::new))
                .param("amount", hold.totalPriceCents())
                .update();
        return orderId;
    }

    private String orderStatus(UUID orderId) {
        return jdbc.sql("SELECT status FROM orders WHERE id = :id").param("id", orderId).query(String.class).single();
    }

    private String paymentIntentStored(UUID orderId) {
        return jdbc.sql("SELECT payment_intent_id FROM orders WHERE id = :id").param("id", orderId).query(String.class).single();
    }

    private int eventsOf(UUID orderId, String type) {
        return jdbc.sql("SELECT count(*) FROM outbox WHERE aggregate_id = :id AND event_type = :type")
                .param("id", orderId.toString())
                .param("type", type)
                .query(Integer.class)
                .single();
    }
}
