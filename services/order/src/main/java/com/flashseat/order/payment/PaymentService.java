package com.flashseat.order.payment;

import com.flashseat.order.domain.Order;
import com.flashseat.order.domain.OrderService;
import com.flashseat.order.domain.OrderStatus;
import com.flashseat.order.inventory.InventoryClient;
import com.flashseat.order.inventory.InventoryHold;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;

@Service
public class PaymentService {

    private static final Set<OrderStatus> PAYABLE = Set.of(OrderStatus.HELD, OrderStatus.PAYMENT_PENDING);

    private final OrderService orders;
    private final InventoryClient inventory;
    private final PaymentGateway gateway;

    public PaymentService(OrderService orders, InventoryClient inventory, PaymentGateway gateway) {
        this.orders = orders;
        this.inventory = inventory;
        this.gateway = gateway;
    }

    /** Starts paying for an order, or returns the payment already started for it. Safe to call again. */
    public PaymentIntent startPayment(UUID orderId, String admissionToken) {
        Order order = orders.findOrder(orderId).orElseThrow(() -> new PaymentException.OrderNotFound(orderId));

        // Inventory checks the token; the hold's owner must also own the order. Anyone else sees "not found".
        InventoryHold hold = inventory.getHold(order.eventId(), order.holdId(), admissionToken);
        if (!hold.userId().equals(order.userId())) {
            throw new PaymentException.OrderNotFound(orderId);
        }
        if (!PAYABLE.contains(order.status())) {
            throw new PaymentException.NotPayable(orderId, order.status());
        }
        if (!hold.status().equals("ACTIVE")) {
            throw new PaymentException.HoldNotActive(orderId, hold.status());
        }

        // The provider call happens outside any database transaction: no locks are held while we wait on it.
        // The order id is the provider's idempotency key, so retries and double clicks reuse the same payment.
        PaymentIntent payment = gateway.createPayment(
                new PaymentRequest(order.id(), order.amountCents(), order.currency(), "order-" + order.id()));

        if (order.status() == OrderStatus.HELD) {
            orders.markPaymentPending(order, payment.id());
        }
        return payment;
    }
}
