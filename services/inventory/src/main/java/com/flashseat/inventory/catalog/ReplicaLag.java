package com.flashseat.inventory.catalog;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** How far behind the primary the replica is, in seconds. 0 when it has applied everything it received. */
@Component
public class ReplicaLag {

    // Measuring "now - last replayed commit" alone would show a growing lag while the primary is simply idle,
    // so a replica that has replayed everything it received counts as zero.
    private static final String LAG_QUERY = """
            SELECT CASE
                WHEN NOT pg_is_in_recovery() THEN 0
                WHEN pg_last_wal_receive_lsn() = pg_last_wal_replay_lsn() THEN 0
                ELSE coalesce(extract(epoch FROM now() - pg_last_xact_replay_timestamp()), 0)
            END
            """;

    private final JdbcClient replica;

    public ReplicaLag(@Qualifier("replicaJdbc") JdbcClient replica, MeterRegistry metrics) {
        this.replica = replica;
        Gauge.builder("flashseat.replica.lag", this::seconds)
                .description("How far the read replica is behind the primary")
                .baseUnit("seconds")
                .register(metrics);
    }

    double seconds() {
        return replica.sql(LAG_QUERY).query(Double.class).single();
    }
}
