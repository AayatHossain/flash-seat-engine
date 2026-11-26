package com.flashseat.order.idempotency;

import com.flashseat.order.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PostgresIdempotencyStoreTest {

    private static final RequestFingerprint REQUEST = new RequestFingerprint("fingerprint-a");
    private static final RequestFingerprint OTHER_REQUEST = new RequestFingerprint("fingerprint-b");

    @Autowired
    private PostgresIdempotencyStore store;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void onlyTheFirstRequestClaimsAKey() {
        String key = newKey();

        assertThat(store.tryStart(key, REQUEST)).isTrue();
        assertThat(store.tryStart(key, REQUEST)).isFalse();
        assertThat(store.find(key)).hasValueSatisfying(record -> {
            assertThat(record.fingerprint()).isEqualTo(REQUEST);
            assertThat(record.isCompleted()).isFalse();
        });
    }

    @Test
    void completedKeyKeepsItsResponse() {
        String key = newKey();
        store.tryStart(key, REQUEST);

        store.complete(key, new StoredResponse(201, "{\"orderId\":\"o-1\"}"));

        assertThat(store.find(key)).hasValueSatisfying(record ->
                assertThat(record.response()).isEqualTo(new StoredResponse(201, "{\"orderId\":\"o-1\"}")));
        assertThat(store.tryStart(key, REQUEST)).isFalse();
    }

    @Test
    void releasedKeyCanBeClaimedAgain() {
        String key = newKey();
        store.tryStart(key, REQUEST);

        store.release(key);

        assertThat(store.find(key)).isEmpty();
        assertThat(store.tryStart(key, REQUEST)).isTrue();
    }

    @Test
    void retryTakesOverAKeyWhoseRequestCrashed() {
        String key = newKey();
        store.tryStart(key, REQUEST);
        expireLease(key);

        assertThat(store.tryStart(key, REQUEST)).isTrue();
    }

    @Test
    void crashedKeyCannotBeTakenOverByADifferentRequest() {
        String key = newKey();
        store.tryStart(key, REQUEST);
        expireLease(key);

        assertThat(store.tryStart(key, OTHER_REQUEST)).isFalse();
    }

    @Test
    void finishedKeyIsNeverTakenOverEvenAfterItsLease() {
        String key = newKey();
        store.tryStart(key, REQUEST);
        store.complete(key, new StoredResponse(201, "{}"));
        expireLease(key);

        assertThat(store.tryStart(key, REQUEST)).isFalse();
    }

    @Test
    void manyRetriesAtOnceClaimTheKeyExactlyOnce() throws Exception {
        String key = newKey();
        List<Future<Boolean>> attempts = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 100; i++) {
                attempts.add(executor.submit(() -> store.tryStart(key, REQUEST)));
            }
        }

        int winners = 0;
        for (Future<Boolean> attempt : attempts) {
            if (attempt.get()) {
                winners++;
            }
        }
        assertThat(winners).isEqualTo(1);
    }

    @Test
    void oldKeysAreCleanedUp() {
        String oldKey = newKey();
        String freshKey = newKey();
        store.tryStart(oldKey, REQUEST);
        store.tryStart(freshKey, REQUEST);
        jdbc.sql("UPDATE idempotency_keys SET created_at = now() - interval '25 hours' WHERE key = :key")
                .param("key", oldKey)
                .update();

        store.deleteExpired();

        assertThat(store.find(oldKey)).isEmpty();
        assertThat(store.find(freshKey)).isPresent();
    }

    private void expireLease(String key) {
        jdbc.sql("UPDATE idempotency_keys SET locked_until = now() - interval '1 second' WHERE key = :key")
                .param("key", key)
                .update();
    }

    private static String newKey() {
        return "key-" + UUID.randomUUID();
    }
}
