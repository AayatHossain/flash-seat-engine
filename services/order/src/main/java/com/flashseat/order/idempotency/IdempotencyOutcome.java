package com.flashseat.order.idempotency;

public sealed interface IdempotencyOutcome {

    /** First time we saw this key: the action ran. */
    record Executed(StoredResponse response) implements IdempotencyOutcome {
    }

    /** A retry of a finished request: the saved response is returned, the action did not run again. */
    record Replayed(StoredResponse response) implements IdempotencyOutcome {
    }

    /** The first request with this key is still running. Maps to HTTP 409. */
    record StillInProgress() implements IdempotencyOutcome {
    }

    /** The key was reused for a different request. Maps to HTTP 422. */
    record KeyReused() implements IdempotencyOutcome {
    }
}
