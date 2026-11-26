package com.flashseat.order.inventory;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "flashseat.inventory")
public record InventoryProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {
}
