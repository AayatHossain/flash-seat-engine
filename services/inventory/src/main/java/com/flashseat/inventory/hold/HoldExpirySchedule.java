package com.flashseat.inventory.hold;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "flashseat.hold.sweeper.enabled", havingValue = "true")
public class HoldExpirySchedule {

    private static final Logger log = LoggerFactory.getLogger(HoldExpirySchedule.class);

    private final HoldExpirySweeper sweeper;

    public HoldExpirySchedule(HoldExpirySweeper sweeper) {
        this.sweeper = sweeper;
    }

    @Scheduled(fixedDelayString = "${flashseat.hold.sweeper.interval}")
    public void run() {
        int expired = sweeper.sweepExpiredHolds();
        if (expired > 0) {
            log.info("Expired {} holds and released their seats", expired);
        }
    }
}
