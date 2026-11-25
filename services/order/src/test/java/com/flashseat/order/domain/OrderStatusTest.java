package com.flashseat.order.domain;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static com.flashseat.order.domain.OrderStatus.CANCELLED;
import static com.flashseat.order.domain.OrderStatus.CREATED;
import static com.flashseat.order.domain.OrderStatus.EXPIRED;
import static com.flashseat.order.domain.OrderStatus.HELD;
import static com.flashseat.order.domain.OrderStatus.PAID;
import static com.flashseat.order.domain.OrderStatus.PAYMENT_PENDING;
import static com.flashseat.order.domain.OrderStatus.REFUNDED;
import static com.flashseat.order.domain.OrderStatus.TICKETED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderStatusTest {

    private record Move(OrderStatus from, OrderStatus to) {
    }

    // Written out by hand on purpose: this is the spec the enum must match.
    private static final Set<Move> EXPECTED_MOVES = Set.of(
            new Move(CREATED, HELD),
            new Move(CREATED, CANCELLED),
            new Move(HELD, PAYMENT_PENDING),
            new Move(HELD, CANCELLED),
            new Move(HELD, EXPIRED),
            new Move(PAYMENT_PENDING, PAID),
            new Move(PAYMENT_PENDING, CANCELLED),
            new Move(PAYMENT_PENDING, EXPIRED),
            new Move(PAID, TICKETED),
            new Move(PAID, REFUNDED),
            new Move(TICKETED, REFUNDED),
            new Move(EXPIRED, PAID),
            new Move(EXPIRED, REFUNDED)
    );

    @Test
    void everyPairOfStatusesMatchesTheSpec() {
        for (OrderStatus from : OrderStatus.values()) {
            for (OrderStatus to : OrderStatus.values()) {
                boolean expected = EXPECTED_MOVES.contains(new Move(from, to));
                assertThat(from.canMoveTo(to))
                        .as("%s -> %s", from, to)
                        .isEqualTo(expected);
            }
        }
    }

    @Test
    void allowedMoveReturnsTheNewStatus() {
        assertThat(HELD.moveTo(PAYMENT_PENDING)).isEqualTo(PAYMENT_PENDING);
    }

    @Test
    void illegalMoveIsRejected() {
        assertThatThrownBy(() -> PAID.moveTo(HELD))
                .isInstanceOf(IllegalStatusTransitionException.class)
                .hasMessage("Order cannot move from PAID to HELD");
    }

    @Test
    void sameStatusTwiceIsNotATransition() {
        for (OrderStatus status : OrderStatus.values()) {
            assertThat(status.canMoveTo(status)).as("%s -> %s", status, status).isFalse();
        }
    }

    @Test
    void onlyCancelledAndRefundedAreFinal() {
        Set<OrderStatus> finalStatuses = EnumSet.noneOf(OrderStatus.class);
        for (OrderStatus status : OrderStatus.values()) {
            if (status.isFinal()) {
                finalStatuses.add(status);
            }
        }
        assertThat(finalStatuses).containsExactlyInAnyOrder(CANCELLED, REFUNDED);
    }

    @Test
    void happyPathReachesTicketed() {
        OrderStatus status = CREATED;
        for (OrderStatus next : List.of(HELD, PAYMENT_PENDING, PAID, TICKETED)) {
            status = status.moveTo(next);
        }
        assertThat(status).isEqualTo(TICKETED);
    }

    @Test
    void latePaymentAfterExpiryCanBeKeptOrRefunded() {
        OrderStatus expired = PAYMENT_PENDING.moveTo(EXPIRED);

        assertThat(expired.canMoveTo(PAID)).isTrue();
        assertThat(expired.canMoveTo(REFUNDED)).isTrue();
        assertThat(expired.canMoveTo(TICKETED)).isFalse();
    }
}
