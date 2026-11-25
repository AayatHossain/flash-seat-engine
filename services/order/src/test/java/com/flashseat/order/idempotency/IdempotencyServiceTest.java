package com.flashseat.order.idempotency;

import com.flashseat.order.idempotency.IdempotencyOutcome.Executed;
import com.flashseat.order.idempotency.IdempotencyOutcome.KeyReused;
import com.flashseat.order.idempotency.IdempotencyOutcome.Replayed;
import com.flashseat.order.idempotency.IdempotencyOutcome.StillInProgress;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyServiceTest {

    private static final StoredResponse CREATED = new StoredResponse(201, "{\"orderId\":\"o-1\"}");

    private final IdempotencyService service = new IdempotencyService(new InMemoryIdempotencyStore());
    private final AtomicInteger actionRuns = new AtomicInteger();

    @Test
    void firstRequestRunsTheAction() {
        IdempotencyOutcome outcome = service.execute("key-1", fingerprint("{\"seats\":2}"), this::createOrder);

        assertThat(outcome).isEqualTo(new Executed(CREATED));
        assertThat(actionRuns).hasValue(1);
    }

    @Test
    void retryReturnsTheSavedResponseWithoutRunningTheActionAgain() {
        service.execute("key-1", fingerprint("{\"seats\":2}"), this::createOrder);

        IdempotencyOutcome retry = service.execute("key-1", fingerprint("{\"seats\":2}"), this::createOrder);

        assertThat(retry).isEqualTo(new Replayed(CREATED));
        assertThat(actionRuns).hasValue(1);
    }

    @Test
    void reusingAKeyForADifferentRequestIsRejected() {
        service.execute("key-1", fingerprint("{\"seats\":2}"), this::createOrder);

        IdempotencyOutcome outcome = service.execute("key-1", fingerprint("{\"seats\":5}"), this::createOrder);

        assertThat(outcome).isEqualTo(new KeyReused());
        assertThat(actionRuns).hasValue(1);
    }

    @Test
    void retryWhileTheFirstRequestIsStillRunningIsRejected() throws Exception {
        CountDownLatch firstRequestStarted = new CountDownLatch(1);
        CountDownLatch letFirstRequestFinish = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<IdempotencyOutcome> first = executor.submit(() ->
                    service.execute("key-1", fingerprint("{}"), () -> {
                        firstRequestStarted.countDown();
                        await(letFirstRequestFinish);
                        return createOrder();
                    }));

            firstRequestStarted.await();
            IdempotencyOutcome retry = service.execute("key-1", fingerprint("{}"), this::createOrder);
            letFirstRequestFinish.countDown();

            assertThat(retry).isEqualTo(new StillInProgress());
            assertThat(first.get()).isEqualTo(new Executed(CREATED));
        }
        assertThat(actionRuns).hasValue(1);
    }

    @Test
    void failedRequestFreesTheKeySoTheClientCanRetry() {
        assertThatThrownBy(() -> service.execute("key-1", fingerprint("{}"), () -> {
            throw new IllegalStateException("payment provider is down");
        })).isInstanceOf(IllegalStateException.class);

        IdempotencyOutcome retry = service.execute("key-1", fingerprint("{}"), this::createOrder);

        assertThat(retry).isEqualTo(new Executed(CREATED));
    }

    @Test
    void manyConcurrentRetriesRunTheActionExactlyOnce() throws Exception {
        List<Future<IdempotencyOutcome>> outcomes = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 200; i++) {
                outcomes.add(executor.submit(() -> service.execute("key-1", fingerprint("{}"), this::createOrder)));
            }
        }

        int executed = 0;
        for (Future<IdempotencyOutcome> outcome : outcomes) {
            if (outcome.get() instanceof Executed) {
                executed++;
            }
        }
        assertThat(executed).isEqualTo(1);
        assertThat(actionRuns).hasValue(1);
    }

    @Test
    void rejectsMissingOrOversizedKeys() {
        RequestFingerprint anyRequest = fingerprint("{}");

        assertThatThrownBy(() -> service.execute(null, anyRequest, this::createOrder))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.execute(" ", anyRequest, this::createOrder))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.execute("k".repeat(256), anyRequest, this::createOrder))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private StoredResponse createOrder() {
        actionRuns.incrementAndGet();
        return CREATED;
    }

    private static RequestFingerprint fingerprint(String body) {
        return RequestFingerprint.of("POST", "/orders", body.getBytes(StandardCharsets.UTF_8));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
