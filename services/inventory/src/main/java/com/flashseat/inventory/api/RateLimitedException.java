package com.flashseat.inventory.api;

import java.time.Duration;

class RateLimitedException extends RuntimeException {

    private final Duration retryAfter;

    RateLimitedException(Duration retryAfter) {
        super("Too many requests, retry after " + retryAfter.toMillis() + " ms");
        this.retryAfter = retryAfter;
    }

    Duration getRetryAfter() {
        return retryAfter;
    }
}
