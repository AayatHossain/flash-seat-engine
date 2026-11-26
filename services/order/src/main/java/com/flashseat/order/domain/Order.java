package com.flashseat.order.domain;

import java.util.List;
import java.util.UUID;

public record Order(UUID id,
                    String userId,
                    UUID eventId,
                    UUID holdId,
                    List<String> seatIds,
                    OrderStatus status,
                    long amountCents,
                    String currency) {
}
