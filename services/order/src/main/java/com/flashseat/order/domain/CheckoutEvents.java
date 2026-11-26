package com.flashseat.order.domain;

import java.util.List;
import java.util.UUID;

/** Events the checkout saga publishes through the outbox. */
public final class CheckoutEvents {

    private CheckoutEvents() {
    }

    public record OrderPaid(UUID orderId, String userId, UUID eventId, UUID holdId, List<String> seatIds,
                            long amountCents, String currency, String paymentIntentId) {

        public static OrderPaid of(Order order) {
            return new OrderPaid(order.id(), order.userId(), order.eventId(), order.holdId(), order.seatIds(),
                    order.amountCents(), order.currency(), order.paymentIntentId());
        }
    }

    public record PaymentFailed(UUID orderId, String userId, String paymentIntentId) {
    }

    public record SeatsLost(UUID orderId, String reason) {
    }

    public record OrderRefunded(UUID orderId, String userId, String refundId, long amountCents, String currency, String reason) {
    }
}
