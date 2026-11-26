package com.flashseat.order.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "flashseat.outbox.publisher.enabled", havingValue = "true")
public class OutboxSchedule {

    private static final Logger log = LoggerFactory.getLogger(OutboxSchedule.class);

    private final OutboxPublisher publisher;

    public OutboxSchedule(OutboxPublisher publisher) {
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${flashseat.outbox.publisher.interval}")
    public void run() {
        try {
            int published = publisher.publishPending();
            if (published > 0) {
                log.debug("Published {} outbox events", published);
            }
        } catch (OutboxPublishException e) {
            // Kafka is unreachable right now. Nothing is lost: the events stay in the outbox for the next run.
            log.warn("Outbox publishing failed, will retry: {}", e.getMessage());
        }
    }
}
