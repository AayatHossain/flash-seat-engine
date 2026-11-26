package com.flashseat.order.domain;

import com.flashseat.order.inventory.InventoryHold;
import com.flashseat.order.outbox.OutboxWriter;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
                OrderStatus.HELD, hold.totalPriceCents(), CURRENCY);
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
}
