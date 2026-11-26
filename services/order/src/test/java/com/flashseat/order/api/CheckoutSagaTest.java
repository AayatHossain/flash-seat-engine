package com.flashseat.order.api;

import com.flashseat.order.TestcontainersConfiguration;
import com.flashseat.order.api.CheckoutTestBeans.SwitchablePaymentGateway;
import com.flashseat.order.inventory.InventoryClient.ConfirmResult;
import com.flashseat.order.inventory.InventoryException;
import com.flashseat.order.payment.WebhookSignatureVerifier;
import org.junit.jupiter.api.AfterEach;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The whole checkout saga, driven by signed webhooks exactly as the payment provider would send them. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, CheckoutTestBeans.class})
class CheckoutSagaTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private FakeInventoryClient inventory;

    @Autowired
    private SwitchablePaymentGateway gateway;

    @Autowired
    private WebhookSignatureVerifier signer;

    @Autowired
    private JdbcClient jdbc;

    @AfterEach
    void providerBackUp() {
        gateway.setDown(false);
    }

    @Test
    void successfulPaymentConfirmsTheSeatsAndMarksTheOrderPaid() throws Exception {
        PendingOrder order = pendingOrder();

        webhook("payment_intent.succeeded", order).andExpect(status().isOk());

        assertThat(statusOf(order)).isEqualTo("PAID");
        assertThat(inventory.confirmCalls(order.holdId())).isEqualTo(1);
        assertThat(eventsOf(order, "OrderPaid")).isEqualTo(1);
    }

    @Test
    void theSameWebhookDeliveredTwiceIsHandledOnce() throws Exception {
        PendingOrder order = pendingOrder();
        String eventId = "evt_" + UUID.randomUUID();

        webhook(eventId, "payment_intent.succeeded", order).andExpect(status().isOk());
        webhook(eventId, "payment_intent.succeeded", order).andExpect(status().isOk());

        assertThat(inventory.confirmCalls(order.holdId())).isEqualTo(1);
        assertThat(eventsOf(order, "OrderPaid")).isEqualTo(1);
    }

    @Test
    void manyParallelDeliveriesStillPayTheOrderOnce() throws Exception {
        PendingOrder order = pendingOrder();

        List<Future<Integer>> deliveries = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 10; i++) {
                deliveries.add(executor.submit(() ->
                        webhook("payment_intent.succeeded", order).andReturn().getResponse().getStatus()));
            }
        }
        for (Future<Integer> delivery : deliveries) {
            assertThat(delivery.get()).isEqualTo(200);
        }

        assertThat(statusOf(order)).isEqualTo("PAID");
        assertThat(eventsOf(order, "OrderPaid")).isEqualTo(1);
    }

    @Test
    void paymentThatArrivesAfterTheHoldExpiredIsRefunded() throws Exception {
        PendingOrder order = pendingOrder();
        inventory.confirmWill(order.holdId(), ConfirmResult.EXPIRED);
        int refundsBefore = gateway.refundCount();

        webhook("payment_intent.succeeded", order).andExpect(status().isOk());

        assertThat(statusOf(order)).isEqualTo("REFUNDED");
        assertThat(gateway.refundCount() - refundsBefore).isEqualTo(1);
        assertThat(eventsOf(order, "SeatsLost")).isEqualTo(1);
        assertThat(eventsOf(order, "OrderRefunded")).isEqualTo(1);
        assertThat(eventsOf(order, "OrderPaid")).isZero();
    }

    @Test
    void inventoryOutageMakesTheProviderRetryAndTheRetryFinishesCheckout() throws Exception {
        PendingOrder order = pendingOrder();
        String eventId = "evt_" + UUID.randomUUID();
        inventory.confirmFailsWith(order.holdId(), new InventoryException.Unavailable("down", null));

        webhook(eventId, "payment_intent.succeeded", order).andExpect(status().isServiceUnavailable());
        assertThat(statusOf(order)).isEqualTo("PAYMENT_PENDING");

        inventory.confirmWorksAgain(order.holdId());
        webhook(eventId, "payment_intent.succeeded", order).andExpect(status().isOk());
        assertThat(statusOf(order)).isEqualTo("PAID");
    }

    @Test
    void refundThatFailsIsRetriedUntilItSucceeds() throws Exception {
        PendingOrder order = pendingOrder();
        String eventId = "evt_" + UUID.randomUUID();
        inventory.confirmWill(order.holdId(), ConfirmResult.EXPIRED);
        gateway.setDown(true);
        int refundsBefore = gateway.refundCount();

        webhook(eventId, "payment_intent.succeeded", order).andExpect(status().isBadGateway());
        assertThat(statusOf(order)).isEqualTo("EXPIRED");

        gateway.setDown(false);
        webhook(eventId, "payment_intent.succeeded", order).andExpect(status().isOk());
        assertThat(statusOf(order)).isEqualTo("REFUNDED");
        assertThat(gateway.refundCount() - refundsBefore).isEqualTo(1);
    }

    @Test
    void failedCardAttemptKeepsTheOrderOpenForAnotherTry() throws Exception {
        PendingOrder order = pendingOrder();

        webhook("payment_intent.payment_failed", order).andExpect(status().isOk());
        assertThat(statusOf(order)).isEqualTo("PAYMENT_PENDING");
        assertThat(eventsOf(order, "PaymentFailed")).isEqualTo(1);

        webhook("payment_intent.succeeded", order).andExpect(status().isOk());
        assertThat(statusOf(order)).isEqualTo("PAID");
    }

    @Test
    void lateFailureWebhookCannotUndoAPaidOrder() throws Exception {
        PendingOrder order = pendingOrder();
        webhook("payment_intent.succeeded", order).andExpect(status().isOk());

        webhook("payment_intent.payment_failed", order).andExpect(status().isOk());

        assertThat(statusOf(order)).isEqualTo("PAID");
        assertThat(eventsOf(order, "PaymentFailed")).isZero();
    }

    @Test
    void unsignedOrForgedWebhooksAreRejected() throws Exception {
        PendingOrder order = pendingOrder();
        String payload = payload("evt_" + UUID.randomUUID(), "payment_intent.succeeded", order.paymentIntentId());

        mvc.perform(post("/payments/webhook").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid-signature"));
        mvc.perform(post("/payments/webhook").contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", "t=" + Instant.now().getEpochSecond() + ",v1=" + "ab".repeat(32))
                        .content(payload))
                .andExpect(status().isBadRequest());

        assertThat(statusOf(order)).isEqualTo("PAYMENT_PENDING");
    }

    @Test
    void webhookForAnUnknownPaymentIsAcknowledgedAndIgnored() throws Exception {
        String payload = payload("evt_" + UUID.randomUUID(), "payment_intent.succeeded", "pi_unknown");

        signedPost(payload).andExpect(status().isOk());
    }

    private record PendingOrder(UUID orderId, UUID eventId, UUID holdId, String paymentIntentId) {
    }

    private PendingOrder pendingOrder() {
        PendingOrder order = new PendingOrder(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "pi_test_" + UUID.randomUUID().toString().replace("-", ""));
        jdbc.sql("""
                        INSERT INTO orders (id, user_id, event_id, hold_id, seat_ids, status, amount_cents, currency, payment_intent_id)
                        VALUES (:id, 'buyer', :eventId, :holdId, ARRAY['A-1'], 'PAYMENT_PENDING', 5000, 'USD', :pi)
                        """)
                .param("id", order.orderId())
                .param("eventId", order.eventId())
                .param("holdId", order.holdId())
                .param("pi", order.paymentIntentId())
                .update();
        return order;
    }

    private ResultActions webhook(String type, PendingOrder order) throws Exception {
        return webhook("evt_" + UUID.randomUUID(), type, order);
    }

    private ResultActions webhook(String eventId, String type, PendingOrder order) throws Exception {
        return signedPost(payload(eventId, type, order.paymentIntentId()));
    }

    private ResultActions signedPost(String payload) throws Exception {
        return mvc.perform(post("/payments/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Stripe-Signature", signer.signatureHeaderFor(payload, Instant.now()))
                .content(payload));
    }

    private static String payload(String eventId, String type, String paymentIntentId) {
        return """
                {"id":"%s","type":"%s","data":{"object":{"id":"%s","object":"payment_intent"}}}
                """.formatted(eventId, type, paymentIntentId);
    }

    private String statusOf(PendingOrder order) {
        return jdbc.sql("SELECT status FROM orders WHERE id = :id").param("id", order.orderId()).query(String.class).single();
    }

    private int eventsOf(PendingOrder order, String type) {
        return jdbc.sql("SELECT count(*) FROM outbox WHERE aggregate_id = :id AND event_type = :type")
                .param("id", order.orderId().toString())
                .param("type", type)
                .query(Integer.class)
                .single();
    }
}
