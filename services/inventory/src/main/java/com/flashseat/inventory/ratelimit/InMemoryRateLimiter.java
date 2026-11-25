package com.flashseat.inventory.ratelimit;

import java.time.InstantSource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Keeps one bucket per key (for example, per user) in this JVM.
 * Limits are per instance only; the Redis version shares them across all replicas.
 */
public class InMemoryRateLimiter {

    private final TokenBucket bucket;
    private final InstantSource clock;
    private final Map<String, BucketState> buckets = new ConcurrentHashMap<>();

    public InMemoryRateLimiter(TokenBucket bucket, InstantSource clock) {
        this.bucket = bucket;
        this.clock = clock;
    }

    public RateLimitDecision tryAcquire(String key) {
        AtomicReference<RateLimitDecision> decision = new AtomicReference<>();
        // compute() runs atomically per key, so two threads can never spend the same token.
        buckets.compute(key, (k, current) -> {
            RateLimitDecision result = bucket.tryConsume(current, clock.instant());
            decision.set(result);
            return result.newState();
        });
        return decision.get();
    }
}
