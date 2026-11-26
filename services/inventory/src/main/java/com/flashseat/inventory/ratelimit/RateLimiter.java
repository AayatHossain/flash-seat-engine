package com.flashseat.inventory.ratelimit;

import java.time.Duration;

public interface RateLimiter {

    /** Spends one token from the caller's bucket, or says how long to wait for the next one. */
    Result tryAcquire(String key);

    record Result(boolean allowed, Duration retryAfter) {

        static Result allow() {
            return new Result(true, Duration.ZERO);
        }
    }
}
