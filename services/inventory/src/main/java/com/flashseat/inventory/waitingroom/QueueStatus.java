package com.flashseat.inventory.waitingroom;

/**
 * Where a buyer stands in the queue. Once admitted, {@code admissionToken} lets them hold seats.
 */
public record QueueStatus(long position,
                          long peopleAhead,
                          boolean admitted,
                          long estimatedWaitSeconds,
                          String admissionToken) {
}
