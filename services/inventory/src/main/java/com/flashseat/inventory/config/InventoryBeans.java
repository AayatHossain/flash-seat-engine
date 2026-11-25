package com.flashseat.inventory.config;

import com.flashseat.inventory.admission.AdmissionProperties;
import com.flashseat.inventory.admission.AdmissionTokenService;
import com.flashseat.inventory.ratelimit.InMemoryRateLimiter;
import com.flashseat.inventory.ratelimit.RateLimitProperties;
import com.flashseat.inventory.ratelimit.TokenBucket;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.time.InstantSource;

@Configuration
public class InventoryBeans {

    @Bean
    InstantSource clock() {
        return InstantSource.system();
    }

    @Bean
    AdmissionTokenService admissionTokenService(AdmissionProperties properties, InstantSource clock) {
        byte[] secret = properties.secret().getBytes(StandardCharsets.UTF_8);
        return new AdmissionTokenService(secret, properties.validity(), clock);
    }

    @Bean
    InMemoryRateLimiter rateLimiter(RateLimitProperties properties, InstantSource clock) {
        TokenBucket bucket = new TokenBucket(properties.capacity(), properties.refillPerSecond());
        return new InMemoryRateLimiter(bucket, clock);
    }
}
