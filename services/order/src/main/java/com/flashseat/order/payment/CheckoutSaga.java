package com.flashseat.order.payment;

import com.flashseat.order.domain.CheckoutEvents;
import com.flashseat.order.domain.Order;
import com.flashseat.order.domain.OrderService;
import com.flashseat.order.domain.OrderStatus;
import com.flashseat.order.inventory.InventoryClient;
import com.flashseat.order.inventory.InventoryClient.ConfirmResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Finishes checkout once the payment provider reports back.
 * <p>
 * Steps for a successful payment: confirm the seats with inventory, then mark the order PAID.
 * If the seats are gone because the hold ran out first, the compensating step refunds the buyer.
 * <p>
 * Every step is safe to repeat: inventory confirm is idempotent, provider calls use idempotency keys,
 * and status changes are conditional. So when anything fails midway, the provider simply delivers the
 * webhook again and the saga continues from wherever it stopped.
 */
@Service
public class CheckoutSaga {

    private static final Logger log = LoggerFactory.getLogger(CheckoutSaga.class);

    private final OrderService orders;
    private final InventoryClient inventory;
    private final PaymentGateway gateway;
    private final WebhookEventLog eventLog;

    public CheckoutSaga(OrderService orders, InventoryClient inventory, PaymentGateway gateway, WebhookEventLog eventLog) {
        this.orders = orders;
        this.inventory = inventory;
        this.gateway = gateway;
        this.eventLog = eventLog;
    }

    public void handle(PaymentEvent event) {
        if (eventLog.alreadyProcessed(event.eventId())) {
            return;
        }
        Optional<Order> order = orders.findByPaymentIntent(event.paymentIntentId());
        if (order.isEmpty()) {
            log.warn("Ignoring {} for unknown payment {}", event.type(), event.paymentIntentId());
        } else {
            switch (event.type()) {
                case SUCCEEDED -> paymentSucceeded(order.get());
                case FAILED -> paymentFailed(order.get());
                case OTHER -> { }
            }
        }
        eventLog.markProcessed(event);
    }

    private void paymentSucceeded(Order order) {
        switch (order.status()) {
            case PAYMENT_PENDING -> confirmSeatsOrRefund(order);
            case EXPIRED -> refund(order, "Seats were released before the payment arrived");
            default -> log.info("Order {} is already {}, nothing to do", order.id(), order.status());
        }
    }

    private void confirmSeatsOrRefund(Order order) {
        ConfirmResult result = inventory.confirmHold(order.eventId(), order.holdId());
        if (result == ConfirmResult.CONFIRMED) {
            orders.transition(order, OrderStatus.PAID, "OrderPaid", CheckoutEvents.OrderPaid.of(order));
            return;
        }
        // The buyer paid, but the seats are no longer theirs. Record that first, then give the money back.
        String reason = result == ConfirmResult.EXPIRED
                ? "Seats were released before the payment arrived"
                : "Seats were no longer held when the payment arrived";
        orders.transition(order, OrderStatus.EXPIRED, "SeatsLost", new CheckoutEvents.SeatsLost(order.id(), reason));
        Order expired = orders.findOrder(order.id()).orElseThrow();
        if (expired.status() == OrderStatus.EXPIRED) {
            refund(expired, reason);
        }
    }

    private void refund(Order order, String reason) {
        String refundId = gateway.refund(order.paymentIntentId(), "refund-" + order.id());
        orders.transition(order, OrderStatus.REFUNDED, "OrderRefunded", new CheckoutEvents.OrderRefunded(
                order.id(), order.userId(), refundId, order.amountCents(), order.currency(), reason));
    }

    /**
     * A failed attempt is not the end of the payment: the buyer can try another card on the same payment.
     * The order keeps waiting; if nobody pays, the seat hold times out and the seats go back on sale.
     */
    private void paymentFailed(Order order) {
        if (order.status() == OrderStatus.PAYMENT_PENDING) {
            orders.recordEvent(order, "PaymentFailed",
                    new CheckoutEvents.PaymentFailed(order.id(), order.userId(), order.paymentIntentId()));
        }
    }
}
