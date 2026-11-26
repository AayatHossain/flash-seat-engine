package com.flashseat.worker.ticket;

/** The message can never be processed, however often it is retried. It goes straight to the dead-letter topic. */
public class InvalidEventException extends RuntimeException {

    public InvalidEventException(String message) {
        super(message);
    }
}
