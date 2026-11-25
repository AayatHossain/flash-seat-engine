package com.flashseat.inventory.hold;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "flashseat.hold.sweeper")
public record SweeperProperties(boolean enabled, Duration interval, int batchSize) {
}
