package com.flashseat.inventory.hold;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Hold(UUID id, UUID eventId, String userId, List<String> seatIds, Instant expiresAt) {
}
