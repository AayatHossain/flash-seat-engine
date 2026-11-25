package com.flashseat.order.domain;

import java.util.Map;
import java.util.Set;

public enum OrderStatus {
    CREATED,
    HELD,
    PAYMENT_PENDING,
    PAID,
    TICKETED,
    CANCELLED,
    EXPIRED,
    REFUNDED;

    // An order only moves along these paths. Anything else is a bug or a stale/duplicate event.
    // EXPIRED -> PAID / REFUNDED covers a payment that arrives after the seat hold has run out:
    // we keep the sale if the seat is still free, otherwise we refund it.
    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED_TRANSITIONS = Map.of(
            CREATED, Set.of(HELD, CANCELLED),
            HELD, Set.of(PAYMENT_PENDING, CANCELLED, EXPIRED),
            PAYMENT_PENDING, Set.of(PAID, CANCELLED, EXPIRED),
            PAID, Set.of(TICKETED, REFUNDED),
            TICKETED, Set.of(REFUNDED),
            EXPIRED, Set.of(PAID, REFUNDED),
            CANCELLED, Set.of(),
            REFUNDED, Set.of()
    );

    public boolean canMoveTo(OrderStatus next) {
        return ALLOWED_TRANSITIONS.get(this).contains(next);
    }

    public OrderStatus moveTo(OrderStatus next) {
        if (!canMoveTo(next)) {
            throw new IllegalStatusTransitionException(this, next);
        }
        return next;
    }

    public boolean isFinal() {
        return ALLOWED_TRANSITIONS.get(this).isEmpty();
    }
}
