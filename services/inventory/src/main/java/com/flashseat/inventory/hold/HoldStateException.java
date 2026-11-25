package com.flashseat.inventory.hold;

import java.util.UUID;

public class HoldStateException extends RuntimeException {

    public HoldStateException(UUID holdId, String reason) {
        super("Hold " + holdId + " " + reason);
    }
}
