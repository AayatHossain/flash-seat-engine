package com.flashseat.inventory.catalog;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param ttl            how long a cached page is served before it is rebuilt
 * @param lockTimeout    how long one request may hold the right to rebuild a page
 * @param waitForRebuild how long other requests wait for that rebuild before loading the page themselves
 */
@ConfigurationProperties(prefix = "flashseat.event-page")
public record EventPageProperties(Duration ttl, Duration lockTimeout, Duration waitForRebuild) {
}
