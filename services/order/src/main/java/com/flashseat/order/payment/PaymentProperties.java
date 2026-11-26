package com.flashseat.order.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "flashseat.payments")
public record PaymentProperties(String provider, String webhookSecret, Duration webhookTolerance) {
}
