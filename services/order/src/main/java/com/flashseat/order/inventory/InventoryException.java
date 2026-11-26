package com.flashseat.order.inventory;

import java.time.Duration;
import java.util.UUID;

/** Everything that can go wrong when asking the inventory service about a hold. */
public abstract sealed class InventoryException extends RuntimeException {

    private InventoryException(String message, Throwable cause) {
        super(message, cause);
    }

    public static final class HoldNotFound extends InventoryException {
        public HoldNotFound(UUID holdId) {
            super("Hold " + holdId + " was not found", null);
        }
    }

    public static final class AdmissionRejected extends InventoryException {
        public AdmissionRejected() {
            super("A valid admission token for this event is required", null);
        }
    }

    public static final class RateLimited extends InventoryException {
        private final Duration retryAfter;

        public RateLimited(Duration retryAfter) {
            super("Too many requests, retry after " + retryAfter.toSeconds() + " s", null);
            this.retryAfter = retryAfter;
        }

        public Duration getRetryAfter() {
            return retryAfter;
        }
    }

    public static final class Unavailable extends InventoryException {
        public Unavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
