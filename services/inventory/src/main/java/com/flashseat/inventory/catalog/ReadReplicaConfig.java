package com.flashseat.inventory.catalog;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.micrometer.MicrometerMetricsTrackerFactory;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;

/**
 * Event pages may be a moment out of date (they are cached for 2 s anyway), so they are read from the
 * replica and take that load off the primary. Holds, orders and everything else that changes data
 * use the primary, so a stale replica can never sell a seat twice.
 * <p>
 * The replica gets its own small pool. Without a replica url the pool points at the primary, so the code
 * path is the same everywhere. Both beans are not default candidates: everything else keeps getting the
 * primary database injected.
 */
@Configuration
public class ReadReplicaConfig {

    @Bean(defaultCandidate = false)
    HikariDataSource replicaDataSource(ReadReplicaProperties replica, JdbcConnectionDetails primary, MeterRegistry metrics) {
        boolean hasReplica = StringUtils.hasText(replica.url());
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setPoolName("replica");
        dataSource.setJdbcUrl(hasReplica ? replica.url() : primary.getJdbcUrl());
        dataSource.setUsername(hasReplica ? replica.username() : primary.getUsername());
        dataSource.setPassword(hasReplica ? replica.password() : primary.getPassword());
        dataSource.setMaximumPoolSize(replica.poolSize());
        dataSource.setReadOnly(true);
        // Give up on a dead replica within about 2 s, so the event page can fall back to the primary in time.
        dataSource.setConnectionTimeout(1_000);
        dataSource.addDataSourceProperty("socketTimeout", "2");
        // Spring Boot only publishes pool metrics for the primary database, so this pool registers its own.
        dataSource.setMetricsTrackerFactory(new MicrometerMetricsTrackerFactory(metrics));
        return dataSource;
    }

    @Bean(defaultCandidate = false)
    JdbcClient replicaJdbc(@Qualifier("replicaDataSource") DataSource replica) {
        return JdbcClient.create(replica);
    }
}
