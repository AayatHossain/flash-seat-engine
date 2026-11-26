package com.flashseat.order.idempotency;

import com.flashseat.order.idempotency.IdempotencyOutcome.Executed;
import com.flashseat.order.idempotency.IdempotencyOutcome.KeyReused;
import com.flashseat.order.idempotency.IdempotencyOutcome.Replayed;
import com.flashseat.order.idempotency.IdempotencyOutcome.StillInProgress;

import org.springframework.transaction.support.TransactionOperations;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Makes a request safe to retry: the same {@code Idempotency-Key} with the same request
 * runs the action once and returns the saved response on every retry.
 */
public class IdempotencyService {

    private static final int MAX_KEY_LENGTH = 255;

    private final IdempotencyStore store;
    private final TransactionOperations transactions;

    public IdempotencyService(IdempotencyStore store, TransactionOperations transactions) {
        this.store = store;
        this.transactions = transactions;
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
            // The action's own writes and the saved response commit together. A crash in between leaves
            // neither behind, so a retry can never find the work done but the response missing.
            response = transactions.execute(status -> {
                StoredResponse result = action.get();
                store.complete(key, result);
                return result;
            });
        } catch (RuntimeException e) {
            // Nothing was saved for this request, so free the key and let the client try again.
            store.release(key);
            throw e;
        }
        return new Executed(response);
    }

    private static void requireValidKey(String key) {
        if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("Idempotency-Key must be 1 to " + MAX_KEY_LENGTH + " characters");
        }
    }
}
