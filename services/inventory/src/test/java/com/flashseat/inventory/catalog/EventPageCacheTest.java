package com.flashseat.inventory.catalog;

import com.flashseat.inventory.TestcontainersConfiguration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs against real Redis. The database is replaced by a counting loader so tests can see every load. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class EventPageCacheTest {

    private static final EventPageProperties FAST = new EventPageProperties(
            Duration.ofMillis(500), Duration.ofSeconds(3), Duration.ofSeconds(2));

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JsonMapper json;

    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();

    @Test
    void secondRequestIsServedFromTheCache() {
        CountingLoader database = new CountingLoader(Duration.ZERO);
        EventPageCache cache = new EventPageCache(redis, json, database, FAST, metrics);
        UUID eventId = UUID.randomUUID();

        EventPage first = cache.get(eventId).orElseThrow();
        EventPage second = cache.get(eventId).orElseThrow();

        assertThat(second).isEqualTo(first);
        assertThat(database.loads()).isEqualTo(1);
    }

    @Test
    void expiredPageIsLoadedAgain() throws Exception {
        CountingLoader database = new CountingLoader(Duration.ZERO);
        EventPageCache cache = new EventPageCache(redis, json, database, FAST, metrics);
        UUID eventId = UUID.randomUUID();

        cache.get(eventId);
        Thread.sleep(FAST.ttl().plusMillis(200));
        cache.get(eventId);

        assertThat(database.loads()).isEqualTo(2);
    }

    @Test
    void crowdHittingAColdPageCausesOneDatabaseLoad() throws Exception {
        // A slow database widens the window in which a stampede could happen.
        CountingLoader database = new CountingLoader(Duration.ofMillis(300));
        EventPageCache cache = new EventPageCache(redis, json, database, FAST, metrics);
        UUID eventId = UUID.randomUUID();

        List<Future<Optional<EventPage>>> visitors = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 300; i++) {
                visitors.add(executor.submit(() -> cache.get(eventId)));
            }
        }

        for (Future<Optional<EventPage>> visitor : visitors) {
            assertThat(visitor.get()).isPresent();
        }
        assertThat(database.loads()).isEqualTo(1);
        assertThat(lookups("rebuilt")).isEqualTo(1);
        assertThat(lookups("hit") + lookups("waited_for_rebuild")).isEqualTo(299);
    }

    @Test
    void unknownEventIsAlsoCachedSoBadLinksCannotHammerTheDatabase() {
        CountingLoader database = new CountingLoader(Duration.ZERO, false);
        EventPageCache cache = new EventPageCache(redis, json, database, FAST, metrics);
        UUID unknown = UUID.randomUUID();

        for (int i = 0; i < 5; i++) {
            assertThat(cache.get(unknown)).isEmpty();
        }
        assertThat(database.loads()).isEqualTo(1);
    }

    @Test
    void pageStillWorksWhenRedisIsDown() {
        LettuceConnectionFactory deadRedis = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("localhost", 1),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(500)).build());
        deadRedis.afterPropertiesSet();
        try {
            CountingLoader database = new CountingLoader(Duration.ZERO);
            EventPageCache cache = new EventPageCache(new StringRedisTemplate(deadRedis), json, database, FAST, metrics);

            assertThat(cache.get(UUID.randomUUID())).isPresent();
            assertThat(lookups("database_fallback")).isEqualTo(1);
        } finally {
            deadRedis.destroy();
        }
    }

    private double lookups(String result) {
        return metrics.counter("flashseat.event_page.lookups", "result", result).count();
    }

    /** Pretends to be the database and counts how often it is asked. */
    private static final class CountingLoader implements Function<UUID, Optional<EventPage>> {

        private final AtomicInteger loads = new AtomicInteger();
        private final Duration delay;
        private final boolean eventExists;

        CountingLoader(Duration delay) {
            this(delay, true);
        }

        CountingLoader(Duration delay, boolean eventExists) {
            this.delay = delay;
            this.eventExists = eventExists;
        }

        @Override
        public Optional<EventPage> apply(UUID eventId) {
            loads.incrementAndGet();
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (!eventExists) {
                return Optional.empty();
            }
            Instant opens = Instant.parse("2026-12-01T10:00:00Z");
            return Optional.of(new EventPage(eventId, "Concert", "Arena", opens.plus(Duration.ofDays(30)), opens, 1,
                    List.of(new EventPage.Seat("A-1", "A", 5000, "AVAILABLE"))));
        }

        int loads() {
            return loads.get();
        }
    }
}
