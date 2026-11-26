package com.flashseat.order.api;

import com.flashseat.order.domain.Order;

import java.util.List;
import java.util.UUID;

public record OrderResponse(UUID orderId,
                            UUID eventId,
                            UUID holdId,
                            List<String> seatIds,
                            String status,
                            long amountCents,
                            String currency) {

    static OrderResponse from(Order order) {
        return new OrderResponse(order.id(), order.eventId(), order.holdId(), order.seatIds(),
                order.status().name(), order.amountCents(), order.currency());
    }
}
