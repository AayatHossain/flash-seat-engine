package com.flashseat.inventory.ratelimit;

import java.time.Instant;

public record BucketState(double tokens, Instant updatedAt) {
}
