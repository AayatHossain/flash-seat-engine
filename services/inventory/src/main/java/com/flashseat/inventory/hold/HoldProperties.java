package com.flashseat.inventory.hold;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "flashseat.hold")
public record HoldProperties(Duration duration, int maxSeatsPerHold) {
}
