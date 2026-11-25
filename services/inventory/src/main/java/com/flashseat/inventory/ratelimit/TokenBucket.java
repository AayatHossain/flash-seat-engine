package com.flashseat.inventory.ratelimit;

import java.time.Duration;
import java.time.Instant;

/**
 * Token bucket rules: a bucket holds up to {@code capacity} tokens, refills at a steady rate,
 * and every request spends one token. Short bursts are allowed, sustained floods are not.
 * <p>
 * This class only does the maths and keeps no state, so the same rules can later run
 * inside Redis and be shared by every service replica.
 */
public final class TokenBucket {

    private final double capacity;
    private final double refillPerSecond;

    public TokenBucket(int capacity, double refillPerSecond) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1");
        }
        if (refillPerSecond <= 0) {
            throw new IllegalArgumentException("refillPerSecond must be positive");
        }
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
    }

    public RateLimitDecision tryConsume(BucketState current, Instant now) {
        BucketState refilled = refill(current, now);

        if (refilled.tokens() >= 1) {
            return RateLimitDecision.allow(new BucketState(refilled.tokens() - 1, now));
        }
        return RateLimitDecision.reject(refilled, timeUntilNextToken(refilled));
    }

    private BucketState refill(BucketState current, Instant now) {
        if (current == null) {
            return new BucketState(capacity, now);
        }
        // A clock that moves backwards must never hand out extra tokens.
        long elapsedMillis = Math.max(0, Duration.between(current.updatedAt(), now).toMillis());
        double tokens = Math.min(capacity, current.tokens() + elapsedMillis * refillPerSecond / 1000);
        return new BucketState(tokens, now);
    }

    private Duration timeUntilNextToken(BucketState state) {
        double missingTokens = 1 - state.tokens();
        return Duration.ofMillis((long) Math.ceil(missingTokens / refillPerSecond * 1000));
    }
}
