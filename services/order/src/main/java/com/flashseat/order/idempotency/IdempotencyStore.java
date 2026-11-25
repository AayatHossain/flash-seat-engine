package com.flashseat.order.idempotency;

import java.util.Optional;

public interface IdempotencyStore {

    /**
     * Claims the key as "in progress". Must be atomic: when two requests race,
     * exactly one of them gets {@code true}.
     */
    boolean tryStart(String key, RequestFingerprint fingerprint);

    Optional<IdempotencyRecord> find(String key);

    void complete(String key, StoredResponse response);

    void release(String key);
}
