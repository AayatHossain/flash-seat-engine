package com.flashseat.order.outbox;

public class OutboxPublishException extends RuntimeException {

    public OutboxPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
