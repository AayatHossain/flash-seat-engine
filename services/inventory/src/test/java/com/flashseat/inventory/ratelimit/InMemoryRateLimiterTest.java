package com.flashseat.inventory.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryRateLimiterTest {

    private static final InstantSource FROZEN_CLOCK = InstantSource.fixed(Instant.parse("2026-12-01T10:00:00Z"));

    @Test
    void eachKeyHasItsOwnBucket() {
        InMemoryRateLimiter limiter = new InMemoryRateLimiter(new TokenBucket(1, 1.0), FROZEN_CLOCK);

        assertThat(limiter.tryAcquire("alice").allowed()).isTrue();
        assertThat(limiter.tryAcquire("alice").allowed()).isFalse();
        assertThat(limiter.tryAcquire("bob").allowed()).isTrue();
    }

    @Test
    void concurrentRequestsNeverSpendMoreTokensThanTheBucketHolds() throws Exception {
        int capacity = 50;
        int requests = 1_000;
        InMemoryRateLimiter limiter = new InMemoryRateLimiter(new TokenBucket(capacity, 1.0), FROZEN_CLOCK);

        List<Future<Boolean>> results = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < requests; i++) {
                results.add(executor.submit(() -> limiter.tryAcquire("same-user").allowed()));
            }
        }

        long allowed = 0;
        for (Future<Boolean> result : results) {
            if (result.get()) {
                allowed++;
            }
        }
        assertThat(allowed).isEqualTo(capacity);
    }
}
