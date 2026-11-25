package com.flashseat.inventory.hold;

import java.util.List;
import java.util.UUID;

public class SeatsUnavailableException extends RuntimeException {

    public SeatsUnavailableException(UUID eventId, List<String> seatIds) {
        super("Seats " + seatIds + " for event " + eventId + " are not all available");
    }
}
