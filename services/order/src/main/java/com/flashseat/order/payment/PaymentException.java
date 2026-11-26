package com.flashseat.order.payment;

import com.flashseat.order.domain.OrderStatus;

import java.util.UUID;

public abstract sealed class PaymentException extends RuntimeException {

    private PaymentException(String message) {
        super(message);
    }

    public static final class OrderNotFound extends PaymentException {
        public OrderNotFound(UUID orderId) {
            super("Order " + orderId + " was not found");
        }
    }

    public static final class NotPayable extends PaymentException {
        public NotPayable(UUID orderId, OrderStatus status) {
            super("Order " + orderId + " is " + status + " and cannot be paid");
        }
    }

    public static final class HoldNotActive extends PaymentException {
        public HoldNotActive(UUID orderId, String holdStatus) {
            super("The seats for order " + orderId + " are no longer held (" + holdStatus + ")");
        }
    }
}
