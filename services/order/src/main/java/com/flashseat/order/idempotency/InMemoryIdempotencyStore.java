package com.flashseat.order.idempotency;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryIdempotencyStore implements IdempotencyStore {

    private final Map<String, IdempotencyRecord> records = new ConcurrentHashMap<>();

    @Override
    public boolean tryStart(String key, RequestFingerprint fingerprint) {
        return records.putIfAbsent(key, IdempotencyRecord.inProgress(key, fingerprint)) == null;
    }

    @Override
    public Optional<IdempotencyRecord> find(String key) {
        return Optional.ofNullable(records.get(key));
    }

    @Override
    public void complete(String key, StoredResponse response) {
        records.computeIfPresent(key, (k, record) -> record.completedWith(response));
    }

    @Override
    public void release(String key) {
        records.remove(key);
    }
}
