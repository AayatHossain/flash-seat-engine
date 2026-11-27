package com.flashseat.inventory.catalog;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Serves event pages from Redis for a short time, so a crowd refreshing the page during a sale
 * costs one database query per page every few seconds instead of one per visitor.
 * <p>
 * Cache stampede protection: when a page expires, only the request that wins a short Redis lock
 * rebuilds it. Everyone else waits briefly for that result instead of all querying the database at once.
 * The page may be a couple of seconds old, which is fine: holding a seat is always checked by Postgres.
 */
public class EventPageCache {

    private static final Logger log = LoggerFactory.getLogger(EventPageCache.class);

    private static final String MISSING = "MISSING";
    private static final Duration POLL_INTERVAL = Duration.ofMillis(25);
    private static final DefaultRedisScript<Long> RELEASE_LOCK = releaseLockScript();

    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final Function<UUID, Optional<EventPage>> loader;
    private final EventPageProperties properties;
    private final MeterRegistry metrics;

    public EventPageCache(StringRedisTemplate redis, JsonMapper json, Function<UUID, Optional<EventPage>> loader,
                          EventPageProperties properties, MeterRegistry metrics) {
        this.redis = redis;
        this.json = json;
        this.loader = loader;
        this.properties = properties;
        this.metrics = metrics;
    }

    public Optional<EventPage> get(UUID eventId) {
        try {
            String cached = redis.opsForValue().get(pageKey(eventId));
            if (cached != null) {
                count(Lookup.HIT);
                return decode(cached);
            }
            return rebuildOrWait(eventId);
        } catch (DataAccessException e) {
            // Redis trouble must not take the event page down; serve it straight from the database.
            log.warn("Event page cache unavailable, reading from the database: {}", e.getMessage());
            count(Lookup.DATABASE_FALLBACK);
            return loader.apply(eventId);
        }
    }

    private Optional<EventPage> rebuildOrWait(UUID eventId) {
        String lockToken = UUID.randomUUID().toString();
        Boolean wonLock = redis.opsForValue().setIfAbsent(lockKey(eventId), lockToken, properties.lockTimeout());
        if (Boolean.TRUE.equals(wonLock)) {
            try {
                Optional<EventPage> page = loader.apply(eventId);
                String encoded = page.map(json::writeValueAsString).orElse(MISSING);
                redis.opsForValue().set(pageKey(eventId), encoded, properties.ttl());
                count(Lookup.REBUILT);
                return page;
            } finally {
                redis.execute(RELEASE_LOCK, List.of(lockKey(eventId)), lockToken);
            }
        }

        Instant giveUpAt = Instant.now().plus(properties.waitForRebuild());
        while (Instant.now().isBefore(giveUpAt)) {
            sleep(POLL_INTERVAL);
            String rebuilt = redis.opsForValue().get(pageKey(eventId));
            if (rebuilt != null) {
                count(Lookup.WAITED_FOR_REBUILD);
                return decode(rebuilt);
            }
        }
        // The rebuild is taking too long (its owner may have crashed). Load it ourselves rather than fail.
        count(Lookup.DATABASE_FALLBACK);
        return loader.apply(eventId);
    }

    /** How a page was served. Every result except REBUILT and DATABASE_FALLBACK spared the database. */
    enum Lookup { HIT, WAITED_FOR_REBUILD, REBUILT, DATABASE_FALLBACK }

    private void count(Lookup lookup) {
        metrics.counter("flashseat.event_page.lookups", "result", lookup.name().toLowerCase()).increment();
    }

    private Optional<EventPage> decode(String cached) {
        return MISSING.equals(cached) ? Optional.empty() : Optional.of(json.readValue(cached, EventPage.class));
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the event page", e);
        }
    }

    private static String pageKey(UUID eventId) {
        return "event-page:" + eventId;
    }

    private static String lockKey(UUID eventId) {
        return "event-page:" + eventId + ":rebuild-lock";
    }

    private static DefaultRedisScript<Long> releaseLockScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("scripts/release-lock.lua")));
        script.setResultType(Long.class);
        return script;
    }
}
