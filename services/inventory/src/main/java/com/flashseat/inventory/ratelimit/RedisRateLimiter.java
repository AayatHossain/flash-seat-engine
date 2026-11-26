package com.flashseat.inventory.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

import java.time.Duration;
import java.util.List;

/** One bucket per key, stored in Redis, so every replica of the service draws from the same bucket. */
public class RedisRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);

    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> TOKEN_BUCKET = tokenBucketScript();

    private final StringRedisTemplate redis;
    private final String capacity;
    private final String refillPerSecond;
    private final String idleSeconds;

    public RedisRateLimiter(StringRedisTemplate redis, int capacity, double refillPerSecond) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1");
        }
        if (refillPerSecond <= 0) {
            throw new IllegalArgumentException("refillPerSecond must be positive");
        }
        this.redis = redis;
        this.capacity = String.valueOf(capacity);
        this.refillPerSecond = String.valueOf(refillPerSecond);
        // Long enough for an idle bucket to refill completely; after that it is the same as a new one.
        this.idleSeconds = String.valueOf((long) Math.ceil(capacity / refillPerSecond) + 60);
    }

    @Override
    public Result tryAcquire(String key) {
        try {
            List<?> answer = redis.execute(TOKEN_BUCKET, List.of("rate-limit:" + key), capacity, refillPerSecond, idleSeconds);
            boolean allowed = ((Number) answer.get(0)).longValue() == 1;
            return new Result(allowed, Duration.ofMillis(((Number) answer.get(1)).longValue()));
        } catch (DataAccessException e) {
            // Fail open: if Redis is down we stop limiting rather than stop selling.
            // The waiting room and the database still protect the system.
            log.warn("Rate limiter unavailable, allowing request: {}", e.getMessage());
            return Result.allow();
        }
    }

    @SuppressWarnings("rawtypes")
    private static DefaultRedisScript<List> tokenBucketScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("scripts/token-bucket.lua")));
        script.setResultType(List.class);
        return script;
    }
}
