package com.flashseat.inventory.ratelimit;

import com.flashseat.inventory.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs against real Redis. Each limiter instance stands in for one replica of the service. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RedisRateLimiterTest {

    @Autowired
    private StringRedisTemplate redis;

    @Test
    void allowsABurstUpToCapacityThenSaysHowLongToWait() {
        RedisRateLimiter limiter = new RedisRateLimiter(redis, 3, 1.0);
        String buyer = newBuyer();

        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire(buyer).allowed()).isTrue();
        }
        RateLimiter.Result refused = limiter.tryAcquire(buyer);

        assertThat(refused.allowed()).isFalse();
        assertThat(refused.retryAfter()).isGreaterThan(Duration.ZERO).isLessThanOrEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void tokensComeBackOverTime() throws Exception {
        RedisRateLimiter limiter = new RedisRateLimiter(redis, 1, 5.0);
        String buyer = newBuyer();
        limiter.tryAcquire(buyer);
        assertThat(limiter.tryAcquire(buyer).allowed()).isFalse();

        Thread.sleep(250);

        assertThat(limiter.tryAcquire(buyer).allowed()).isTrue();
    }

    @Test
    void eachBuyerHasTheirOwnBucket() {
        RedisRateLimiter limiter = new RedisRateLimiter(redis, 1, 0.01);
        String alice = newBuyer();
        String bob = newBuyer();

        assertThat(limiter.tryAcquire(alice).allowed()).isTrue();
        assertThat(limiter.tryAcquire(alice).allowed()).isFalse();
        assertThat(limiter.tryAcquire(bob).allowed()).isTrue();
    }

    @Test
    void replicasShareOneLimitSoMoreServersDoNotMeanMoreRequests() {
        RedisRateLimiter replicaA = new RedisRateLimiter(redis, 5, 0.01);
        RedisRateLimiter replicaB = new RedisRateLimiter(redis, 5, 0.01);
        RedisRateLimiter replicaC = new RedisRateLimiter(redis, 5, 0.01);
        String buyer = newBuyer();

        int allowed = 0;
        for (int round = 0; round < 3; round++) {
            for (RedisRateLimiter replica : List.of(replicaA, replicaB, replicaC)) {
                if (replica.tryAcquire(buyer).allowed()) {
                    allowed++;
                }
            }
        }

        assertThat(allowed).isEqualTo(5);
    }

    @Test
    void thousandsOfParallelRequestsAcrossReplicasNeverExceedTheLimit() throws Exception {
        List<RedisRateLimiter> replicas = List.of(
                new RedisRateLimiter(redis, 50, 0.001),
                new RedisRateLimiter(redis, 50, 0.001),
                new RedisRateLimiter(redis, 50, 0.001));
        String buyer = newBuyer();

        List<Future<Boolean>> requests = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 1500; i++) {
                RedisRateLimiter replica = replicas.get(i % replicas.size());
                requests.add(executor.submit(() -> replica.tryAcquire(buyer).allowed()));
            }
        }

        long allowed = 0;
        for (Future<Boolean> request : requests) {
            if (request.get()) {
                allowed++;
            }
        }
        assertThat(allowed).isEqualTo(50);
    }

    @Test
    void failsOpenWhenRedisIsUnreachable() {
        LettuceConnectionFactory deadRedis = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("localhost", 1),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(500)).build());
        deadRedis.afterPropertiesSet();
        try {
            RedisRateLimiter limiter = new RedisRateLimiter(new StringRedisTemplate(deadRedis), 1, 1.0);

            assertThat(limiter.tryAcquire("anyone").allowed()).isTrue();
            assertThat(limiter.tryAcquire("anyone").allowed()).isTrue();
        } finally {
            deadRedis.destroy();
        }
    }

    private static String newBuyer() {
        return "buyer-" + UUID.randomUUID();
    }
}
