package com.flashseat.inventory.catalog;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Connection to the read-only copy of the database. Without a url, event pages are read from the primary. */
@ConfigurationProperties("flashseat.read-replica")
public record ReadReplicaProperties(String url, String username, String password, int poolSize) {
}
