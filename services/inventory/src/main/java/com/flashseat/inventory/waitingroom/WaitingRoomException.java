package com.flashseat.inventory.waitingroom;

import java.util.UUID;

public abstract sealed class WaitingRoomException extends RuntimeException {

    private WaitingRoomException(String message) {
        super(message);
    }

    public static final class EventNotFound extends WaitingRoomException {
        public EventNotFound(UUID eventId) {
            super("Event " + eventId + " does not exist");
        }
    }

    public static final class NotInQueue extends WaitingRoomException {
        public NotInQueue(UUID eventId) {
            super("You have not joined the queue for event " + eventId);
        }
    }
}
