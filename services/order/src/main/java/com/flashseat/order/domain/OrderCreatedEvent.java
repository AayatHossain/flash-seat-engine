package com.flashseat.order.domain;

import java.util.List;
import java.util.UUID;

public record OrderCreatedEvent(UUID orderId,
                                String userId,
                                UUID eventId,
                                UUID holdId,
                                List<String> seatIds,
                                long amountCents,
                                String currency) {

    static OrderCreatedEvent from(Order order) {
        return new OrderCreatedEvent(order.id(), order.userId(), order.eventId(), order.holdId(),
                order.seatIds(), order.amountCents(), order.currency());
    }
}
