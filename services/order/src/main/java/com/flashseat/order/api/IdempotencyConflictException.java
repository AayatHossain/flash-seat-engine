package com.flashseat.order.api;

class IdempotencyConflictException extends RuntimeException {

    private final boolean keyReused;

    private IdempotencyConflictException(String message, boolean keyReused) {
        super(message);
        this.keyReused = keyReused;
    }

    static IdempotencyConflictException stillInProgress() {
        return new IdempotencyConflictException("A request with this Idempotency-Key is still being processed", false);
    }

    static IdempotencyConflictException keyReused() {
        return new IdempotencyConflictException("This Idempotency-Key was already used for a different request", true);
    }

    boolean isKeyReused() {
        return keyReused;
    }
}
