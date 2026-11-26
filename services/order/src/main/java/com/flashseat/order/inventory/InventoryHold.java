package com.flashseat.order.inventory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A seat hold as the inventory service reports it. */
public record InventoryHold(UUID holdId,
                            UUID eventId,
                            String userId,
                            String status,
                            List<String> seatIds,
                            long totalPriceCents,
                            Instant expiresAt) {
}
