package com.flashseat.worker.ticket;

import java.util.UUID;

public record Ticket(UUID id, UUID orderId, UUID eventId, String seatId, String userId, String code) {
}
