package com.flashseat.inventory.waitingroom;

import com.flashseat.inventory.admission.AdmissionTokenService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A virtual queue in front of checkout. Buyers get positions in the order they arrive, and are let in
 * at a fixed rate once the sale opens, so checkout only ever sees as many buyers as it can handle.
 * <p>
 * Who gets in is worked out from the clock: {@code (seconds since the sale opened) × admitPerSecond}
 * positions are admitted. Every replica computes the same number, so no background job and no leader
 * election are needed to open the gate.
 */
@Service
public class WaitingRoom {

    private static final DefaultRedisScript<Long> JOIN_SCRIPT = joinScript();

    private final StringRedisTemplate redis;
    private final JdbcClient jdbc;
    private final AdmissionTokenService admissions;
    private final InstantSource clock;
    private final WaitingRoomProperties properties;

    // Sale opening times never change once an event is on sale, so they are cached instead of
    // read from Postgres on every status poll.
    private final Map<UUID, Instant> saleOpensAt = new ConcurrentHashMap<>();

    public WaitingRoom(StringRedisTemplate redis, JdbcClient jdbc, AdmissionTokenService admissions,
                       InstantSource clock, WaitingRoomProperties properties) {
        this.redis = redis;
        this.jdbc = jdbc;
        this.admissions = admissions;
        this.clock = clock;
        this.properties = properties;
    }

    public QueueStatus join(UUID eventId, String userId) {
        saleOpeningOf(eventId);
        Long position = redis.execute(JOIN_SCRIPT,
                List.of(positionsKey(eventId), counterKey(eventId)),
                userId, String.valueOf(properties.queueRetention().toSeconds()));
        return statusAt(eventId, userId, position);
    }

    public QueueStatus status(UUID eventId, String userId) {
        saleOpeningOf(eventId);
        Object position = redis.opsForHash().get(positionsKey(eventId), userId);
        if (position == null) {
            throw new WaitingRoomException.NotInQueue(eventId);
        }
        return statusAt(eventId, userId, Long.parseLong(position.toString()));
    }

    private QueueStatus statusAt(UUID eventId, String userId, long position) {
        long admittedUpTo = admittedUpTo(eventId);
        if (position <= admittedUpTo) {
            return new QueueStatus(position, 0, true, 0, admissions.issue(userId, eventId.toString()));
        }
        long peopleAhead = position - admittedUpTo - 1;
        long secondsUntilOpen = Math.max(0, Duration.between(clock.instant(), saleOpeningOf(eventId)).toSeconds());
        long estimatedWait = secondsUntilOpen + (long) Math.ceil((position - admittedUpTo) / properties.admitPerSecond());
        return new QueueStatus(position, peopleAhead, false, estimatedWait, null);
    }

    private long admittedUpTo(UUID eventId) {
        Duration sinceOpening = Duration.between(saleOpeningOf(eventId), clock.instant());
        if (sinceOpening.isNegative()) {
            return 0;
        }
        return (long) Math.floor(sinceOpening.toMillis() / 1000.0 * properties.admitPerSecond());
    }

    private Instant saleOpeningOf(UUID eventId) {
        return saleOpensAt.computeIfAbsent(eventId, id -> jdbc.sql("SELECT sale_opens_at FROM events WHERE id = :id")
                .param("id", id)
                .query(OffsetDateTime.class)
                .optional()
                .map(OffsetDateTime::toInstant)
                .orElseThrow(() -> new WaitingRoomException.EventNotFound(id)));
    }

    private static DefaultRedisScript<Long> joinScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("scripts/join-queue.lua")));
        script.setResultType(Long.class);
        return script;
    }

    private static String positionsKey(UUID eventId) {
        return "waiting-room:" + eventId + ":positions";
    }

    private static String counterKey(UUID eventId) {
        return "waiting-room:" + eventId + ":last-position";
    }
}
