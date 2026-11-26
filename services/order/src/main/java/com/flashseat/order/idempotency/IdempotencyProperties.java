package com.flashseat.order.idempotency;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "flashseat.idempotency")
public record IdempotencyProperties(Duration lease, Duration retention) {
}
