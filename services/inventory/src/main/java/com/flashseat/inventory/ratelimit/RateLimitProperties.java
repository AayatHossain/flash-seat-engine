package com.flashseat.inventory.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "flashseat.rate-limit")
public record RateLimitProperties(int capacity, double refillPerSecond) {
}
