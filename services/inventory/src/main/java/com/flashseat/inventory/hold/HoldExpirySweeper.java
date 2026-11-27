package com.flashseat.inventory.hold;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Turns holds whose time ran out into EXPIRED and gives their seats back to the sale.
 * Works in small batches, each in its own short transaction, so it never holds locks for long.
 */
@Component
public class HoldExpirySweeper {

    // Any fixed number works, as long as every replica uses the same one.
    private static final long SWEEPER_LOCK_KEY = 7_340_001L;

    private static final String EXPIRE_ONE_BATCH = """
            WITH expired AS (
                UPDATE holds
                SET status = 'EXPIRED'
                WHERE (event_id, id) IN (
                    SELECT event_id, id
                    FROM holds
                    WHERE status = 'ACTIVE' AND expires_at <= now()
                    ORDER BY expires_at
                    LIMIT :batchSize
                    FOR UPDATE SKIP LOCKED)
                RETURNING event_id, id
            ), freed_seats AS (
                UPDATE seats s
                SET status = 'AVAILABLE', hold_id = NULL, version = s.version + 1
                FROM expired e
                WHERE s.event_id = e.event_id AND s.hold_id = e.id AND s.status = 'HELD'
            )
            SELECT count(*) FROM expired
            """;

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final int batchSize;
    private final Counter holdsExpired;

    public HoldExpirySweeper(JdbcClient jdbc, PlatformTransactionManager transactionManager, SweeperProperties properties,
                             MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.batchSize = properties.batchSize();
        this.holdsExpired = metrics.counter("flashseat.holds.expired");
    }

    /** Returns how many holds this call expired. */
    public int sweepExpiredHolds() {
        int total = 0;
        int expiredInBatch;
        do {
            expiredInBatch = transactions.execute(status -> expireOneBatch());
            holdsExpired.increment(expiredInBatch);
            total += expiredInBatch;
        } while (expiredInBatch == batchSize);
        return total;
    }

    private int expireOneBatch() {
        // Leader election: only the replica that gets this lock sweeps. The lock is released
        // automatically when the transaction ends, so a crashed replica can never keep it.
        boolean isLeader = jdbc.sql("SELECT pg_try_advisory_xact_lock(:key)")
                .param("key", SWEEPER_LOCK_KEY)
                .query(Boolean.class)
                .single();
        if (!isLeader) {
            return 0;
        }

        // SKIP LOCKED: holds that a buyer is confirming right now are left alone, so the sweeper never waits on them.
        return jdbc.sql(EXPIRE_ONE_BATCH)
                .param("batchSize", batchSize)
                .query(Integer.class)
                .single();
    }
}
