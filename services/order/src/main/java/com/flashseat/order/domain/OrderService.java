package com.flashseat.order.domain;

import com.flashseat.order.inventory.InventoryHold;
import com.flashseat.order.outbox.OutboxWriter;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class OrderService {

    private static final String CURRENCY = "USD";

    private final JdbcClient jdbc;
    private final OutboxWriter outbox;

    public OrderService(JdbcClient jdbc, OutboxWriter outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    /** Creates an order for a hold the buyer already owns. The order row and its event commit together. */
    @Transactional
    public Order createOrder(UUID eventId, InventoryHold hold) {
        if (!hold.eventId().equals(eventId)) {
            throw new IllegalArgumentException("Hold " + hold.holdId() + " does not belong to event " + eventId);
        }
        if (!hold.status().equals("ACTIVE")) {
            throw new OrderCreationException.HoldNotActive(hold.holdId(), hold.status());
        }

        Order order = new Order(UUID.randomUUID(), hold.userId(), eventId, hold.holdId(), hold.seatIds(),
                OrderStatus.HELD, hold.totalPriceCents(), CURRENCY, null);
        try {
            jdbc.sql("""
                            INSERT INTO orders (id, user_id, event_id, hold_id, seat_ids, status, amount_cents, currency)
                            VALUES (:id, :userId, :eventId, :holdId, :seatIds, :status, :amountCents, :currency)
                            """)
                    .param("id", order.id())
                    .param("userId", order.userId())
                    .param("eventId", order.eventId())
                    .param("holdId", order.holdId())
                    .param("seatIds", order.seatIds().toArray(String[]::new))
                    .param("status", order.status().name())
                    .param("amountCents", order.amountCents())
                    .param("currency", order.currency())
                    .update();
        } catch (DuplicateKeyException e) {
            throw new OrderCreationException.AlreadyOrdered(hold.holdId());
        }

        outbox.append("order", order.id().toString(), "OrderCreated", OrderCreatedEvent.from(order));
        return order;
    }

    public Optional<Order> findOrder(UUID orderId) {
        return jdbc.sql(SELECT_ORDER + " WHERE id = :id")
                .param("id", orderId)
                .query(OrderService::mapOrder)
                .optional();
    }

    public Optional<Order> findByPaymentIntent(String paymentIntentId) {
        return jdbc.sql(SELECT_ORDER + " WHERE payment_intent_id = :paymentIntentId")
                .param("paymentIntentId", paymentIntentId)
                .query(OrderService::mapOrder)
                .optional();
    }

    /**
     * Moves an order to the next status and records the matching event, in one transaction.
     * The update only applies if the order is still in the status we read, so when two webhooks race,
     * exactly one of them makes the change and writes the event. Returns whether this call made it.
     */
    @Transactional
    public boolean transition(Order order, OrderStatus next, String eventType, Object event) {
        order.status().moveTo(next);
        int updated = jdbc.sql("""
                        UPDATE orders SET status = :next, version = version + 1, updated_at = now()
                        WHERE id = :id AND status = :current
                        """)
                .param("next", next.name())
                .param("id", order.id())
                .param("current", order.status().name())
                .update();
        if (updated == 0) {
            return false;
        }
        outbox.append("order", order.id().toString(), eventType, event);
        return true;
    }

    /** Records something that happened to an order without changing its status. */
    @Transactional
    public void recordEvent(Order order, String eventType, Object event) {
        outbox.append("order", order.id().toString(), eventType, event);
    }

    private static final String SELECT_ORDER = """
            SELECT id, user_id, event_id, hold_id, seat_ids, status, amount_cents, currency, payment_intent_id
            FROM orders""";

    private static Order mapOrder(ResultSet row, int rowNum) throws SQLException {
        return new Order(
                row.getObject("id", UUID.class),
                row.getString("user_id"),
                row.getObject("event_id", UUID.class),
                row.getObject("hold_id", UUID.class),
                List.of((String[]) row.getArray("seat_ids").getArray()),
                OrderStatus.valueOf(row.getString("status")),
                row.getLong("amount_cents"),
                row.getString("currency"),
                row.getString("payment_intent_id"));
    }

    /**
     * Moves a HELD order to PAYMENT_PENDING and remembers its payment. Returns false when the order
     * was no longer HELD, for example because a parallel request already did this.
     */
    @Transactional
    public boolean markPaymentPending(Order order, String paymentIntentId) {
        OrderStatus next = order.status().moveTo(OrderStatus.PAYMENT_PENDING);
        int updated = jdbc.sql("""
                        UPDATE orders
                        SET status = :next, payment_intent_id = :paymentIntentId, version = version + 1, updated_at = now()
                        WHERE id = :id AND status = :current
                        """)
                .param("next", next.name())
                .param("paymentIntentId", paymentIntentId)
                .param("id", order.id())
                .param("current", order.status().name())
                .update();
        if (updated == 0) {
            return false;
        }
        outbox.append("order", order.id().toString(), "PaymentStarted",
                new PaymentStartedEvent(order.id(), paymentIntentId, order.amountCents(), order.currency()));
        return true;
    }
}
