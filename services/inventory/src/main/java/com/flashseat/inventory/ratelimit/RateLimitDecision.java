package com.flashseat.inventory.ratelimit;

import java.time.Duration;

public record RateLimitDecision(boolean allowed, BucketState newState, Duration retryAfter) {

    static RateLimitDecision allow(BucketState newState) {
        return new RateLimitDecision(true, newState, Duration.ZERO);
    }

    static RateLimitDecision reject(BucketState newState, Duration retryAfter) {
        return new RateLimitDecision(false, newState, retryAfter);
    }
}
