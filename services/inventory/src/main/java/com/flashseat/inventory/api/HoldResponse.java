package com.flashseat.inventory.api;

import com.flashseat.inventory.hold.Hold;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record HoldResponse(UUID holdId, UUID eventId, String status, List<String> seatIds, Instant expiresAt) {

    static HoldResponse from(Hold hold) {
        return new HoldResponse(hold.id(), hold.eventId(), hold.status(), hold.seatIds(), hold.expiresAt());
    }
}
