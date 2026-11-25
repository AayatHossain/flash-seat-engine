package com.flashseat.order.idempotency;

public record IdempotencyRecord(String key, RequestFingerprint fingerprint, StoredResponse response) {

    public static IdempotencyRecord inProgress(String key, RequestFingerprint fingerprint) {
        return new IdempotencyRecord(key, fingerprint, null);
    }

    public IdempotencyRecord completedWith(StoredResponse response) {
        return new IdempotencyRecord(key, fingerprint, response);
    }

    public boolean isCompleted() {
        return response != null;
    }
}
