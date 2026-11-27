package com.flashseat.inventory.config;

import com.flashseat.inventory.admission.AdmissionProperties;
import com.flashseat.inventory.admission.AdmissionTokenService;
import com.flashseat.inventory.catalog.EventCatalog;
import com.flashseat.inventory.catalog.EventPageCache;
import com.flashseat.inventory.catalog.EventPageProperties;
import com.flashseat.inventory.ratelimit.InMemoryRateLimiter;
import com.flashseat.inventory.ratelimit.RateLimitProperties;
import com.flashseat.inventory.ratelimit.RateLimiter;
import com.flashseat.inventory.ratelimit.RedisRateLimiter;
import com.flashseat.inventory.ratelimit.TokenBucket;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

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
    EventPageCache eventPageCache(StringRedisTemplate redis, JsonMapper json, EventCatalog catalog,
                                  EventPageProperties properties, MeterRegistry metrics) {
        return new EventPageCache(redis, json, catalog::loadEventPage, properties, metrics);
    }

    @Bean
    @ConditionalOnProperty(name = "flashseat.rate-limit.store", havingValue = "redis", matchIfMissing = true)
    RateLimiter redisRateLimiter(StringRedisTemplate redis, RateLimitProperties properties) {
        return new RedisRateLimiter(redis, properties.capacity(), properties.refillPerSecond());
    }

    @Bean
    @ConditionalOnProperty(name = "flashseat.rate-limit.store", havingValue = "memory")
    RateLimiter inMemoryRateLimiter(RateLimitProperties properties, InstantSource clock) {
        TokenBucket bucket = new TokenBucket(properties.capacity(), properties.refillPerSecond());
        return new InMemoryRateLimiter(bucket, clock);
    }
}
