package com.flashseat.inventory.waitingroom;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param admitPerSecond how many queued buyers are let through to checkout each second, sized to what checkout can handle
 * @param queueRetention how long queue data is kept in Redis
 */
@ConfigurationProperties(prefix = "flashseat.waiting-room")
public record WaitingRoomProperties(double admitPerSecond, Duration queueRetention) {
}
