package com.flashseat.inventory.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenBucketTest {

    private static final Instant START = Instant.parse("2026-12-01T10:00:00Z");

    private final TokenBucket bucket = new TokenBucket(3, 2.0);

    @Test
    void newBucketStartsFullAndAllowsABurstUpToCapacity() {
        BucketState state = null;
        for (int i = 0; i < 3; i++) {
            RateLimitDecision decision = bucket.tryConsume(state, START);
            assertThat(decision.allowed()).isTrue();
            state = decision.newState();
        }

        assertThat(bucket.tryConsume(state, START).allowed()).isFalse();
    }

    @Test
    void rejectedRequestSaysHowLongToWait() {
        BucketState empty = new BucketState(0, START);

        RateLimitDecision decision = bucket.tryConsume(empty, START);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfter()).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    void tokensRefillOverTime() {
        BucketState empty = new BucketState(0, START);

        assertThat(bucket.tryConsume(empty, START.plusMillis(499)).allowed()).isFalse();
        assertThat(bucket.tryConsume(empty, START.plusMillis(500)).allowed()).isTrue();
    }

    @Test
    void longIdleTimeNeverGoesAboveCapacity() {
        BucketState state = new BucketState(0, START);
        Instant anHourLater = START.plus(Duration.ofHours(1));

        int allowed = 0;
        for (int i = 0; i < 10; i++) {
            RateLimitDecision decision = bucket.tryConsume(state, anHourLater);
            if (decision.allowed()) {
                allowed++;
            }
            state = decision.newState();
        }

        assertThat(allowed).isEqualTo(3);
    }

    @Test
    void clockMovingBackwardsAddsNoTokens() {
        BucketState empty = new BucketState(0, START);

        RateLimitDecision decision = bucket.tryConsume(empty, START.minusSeconds(10));

        assertThat(decision.allowed()).isFalse();
    }

    @Test
    void rejectsInvalidSettings() {
        assertThatThrownBy(() -> new TokenBucket(0, 1.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenBucket(5, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
