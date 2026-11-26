package com.flashseat.order.api;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public record CreateOrderRequest(UUID eventId, UUID holdId) {

    void requireComplete() {
        if (eventId == null || holdId == null) {
            throw new IllegalArgumentException("eventId and holdId are required");
        }
    }

    // Built from the parsed fields, so the same request sent with different JSON spacing still counts as a retry.
    byte[] canonicalBytes() {
        return (eventId + "|" + holdId).getBytes(StandardCharsets.UTF_8);
    }
}
