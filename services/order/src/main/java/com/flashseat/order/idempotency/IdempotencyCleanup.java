package com.flashseat.order.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "flashseat.idempotency.cleanup.enabled", havingValue = "true")
public class IdempotencyCleanup {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanup.class);

    private final PostgresIdempotencyStore store;

    public IdempotencyCleanup(PostgresIdempotencyStore store) {
        this.store = store;
    }

    @Scheduled(fixedDelayString = "${flashseat.idempotency.cleanup.interval}")
    public void run() {
        int removed = store.deleteExpired();
        if (removed > 0) {
            log.info("Removed {} expired idempotency keys", removed);
        }
    }
}
