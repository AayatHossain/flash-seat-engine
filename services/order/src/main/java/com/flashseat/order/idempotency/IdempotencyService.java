package com.flashseat.order.idempotency;

import com.flashseat.order.idempotency.IdempotencyOutcome.Executed;
import com.flashseat.order.idempotency.IdempotencyOutcome.KeyReused;
import com.flashseat.order.idempotency.IdempotencyOutcome.Replayed;
import com.flashseat.order.idempotency.IdempotencyOutcome.StillInProgress;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Makes a request safe to retry: the same {@code Idempotency-Key} with the same request
 * runs the action once and returns the saved response on every retry.
 */
public class IdempotencyService {

    private static final int MAX_KEY_LENGTH = 255;

    private final IdempotencyStore store;

    public IdempotencyService(IdempotencyStore store) {
        this.store = store;
    }

    public IdempotencyOutcome execute(String key, RequestFingerprint fingerprint, Supplier<StoredResponse> action) {
        requireValidKey(key);

        if (store.tryStart(key, fingerprint)) {
            return runAndRemember(key, action);
        }

        Optional<IdempotencyRecord> existing = store.find(key);
        if (existing.isEmpty()) {
            // The first request failed and released the key a moment ago; the client can retry.
            return new StillInProgress();
        }
        IdempotencyRecord record = existing.get();
        if (!record.fingerprint().equals(fingerprint)) {
            return new KeyReused();
        }
        if (record.isCompleted()) {
            return new Replayed(record.response());
        }
        return new StillInProgress();
    }

    private IdempotencyOutcome runAndRemember(String key, Supplier<StoredResponse> action) {
        StoredResponse response;
        try {
            response = action.get();
        } catch (RuntimeException e) {
            // Nothing was saved for this request, so free the key and let the client try again.
            store.release(key);
            throw e;
        }
        store.complete(key, response);
        return new Executed(response);
    }

    private static void requireValidKey(String key) {
        if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("Idempotency-Key must be 1 to " + MAX_KEY_LENGTH + " characters");
        }
    }
}
