package com.flashseat.order.payment;

import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Behaves like Stripe for the parts checkout relies on: payment ids look like {@code pi_...}, and an
 * idempotency key always returns the payment it first created. Payments live in memory only.
 */
public class FakePaymentGateway implements PaymentGateway {

    private final Map<String, PaymentIntent> paymentsByKey = new ConcurrentHashMap<>();

    @Override
    public PaymentIntent createPayment(PaymentRequest request) {
        PaymentIntent payment = paymentsByKey.computeIfAbsent(request.idempotencyKey(), key -> newPayment(request));

        // Stripe refuses to reuse a key for different parameters, and so do we.
        if (payment.amountCents() != request.amountCents() || !payment.currency().equals(request.currency())) {
            throw new PaymentGatewayException("Idempotency key " + request.idempotencyKey() + " was used with different parameters", null);
        }
        return payment;
    }

    public int paymentCount() {
        return paymentsByKey.size();
    }

    private static PaymentIntent newPayment(PaymentRequest request) {
        String id = "pi_fake_" + UUID.randomUUID().toString().replace("-", "");
        byte[] secret = new byte[16];
        ThreadLocalRandom.current().nextBytes(secret);
        return new PaymentIntent(id, id + "_secret_" + HexFormat.of().formatHex(secret), request.amountCents(), request.currency());
    }
}
