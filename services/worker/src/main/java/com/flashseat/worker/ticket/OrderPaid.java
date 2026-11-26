package com.flashseat.worker.ticket;

import java.util.List;
import java.util.UUID;

/** The "OrderPaid" event as the order service publishes it. */
public record OrderPaid(UUID orderId,
                        String userId,
                        UUID eventId,
                        UUID holdId,
                        List<String> seatIds,
                        long amountCents,
                        String currency,
                        String paymentIntentId) {

    public void requireComplete() {
        if (orderId == null || userId == null || eventId == null || seatIds == null || seatIds.isEmpty()) {
            throw new InvalidEventException("OrderPaid event is missing required fields");
        }
    }
}
