package com.flashseat.order.domain;

import java.util.UUID;

public abstract sealed class OrderCreationException extends RuntimeException {

    private OrderCreationException(String message) {
        super(message);
    }

    public static final class HoldNotActive extends OrderCreationException {
        public HoldNotActive(UUID holdId, String status) {
            super("Hold " + holdId + " is " + status + " and can no longer be ordered");
        }
    }

    public static final class AlreadyOrdered extends OrderCreationException {
        public AlreadyOrdered(UUID holdId) {
            super("Hold " + holdId + " already has an order");
        }
    }
}
