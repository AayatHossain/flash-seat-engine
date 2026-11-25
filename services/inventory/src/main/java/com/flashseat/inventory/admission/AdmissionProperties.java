package com.flashseat.inventory.admission;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "flashseat.admission")
public record AdmissionProperties(String secret, Duration validity) {
}
