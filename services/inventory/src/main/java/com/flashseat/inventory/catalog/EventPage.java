package com.flashseat.inventory.catalog;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Everything a buyer sees on an event's page. Seats are only AVAILABLE or UNAVAILABLE; who holds them is private. */
public record EventPage(UUID eventId,
                        String name,
                        String venue,
                        Instant startsAt,
                        Instant saleOpensAt,
                        long availableSeats,
                        List<Seat> seats) {

    public record Seat(String seatId, String section, long priceCents, String status) {
    }
}
