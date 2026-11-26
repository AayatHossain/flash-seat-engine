package com.flashseat.order.payment;

/**
 * The payment provider as the order service sees it. Stripe is one implementation; a fake one is used
 * for local runs and tests. Implementations must honour idempotency keys: the same key always returns
 * the same result, so a retried request can never charge or refund twice.
 */
public interface PaymentGateway {

    PaymentIntent createPayment(PaymentRequest request);

    /** Refunds a captured payment in full. Returns the provider's refund id. */
    String refund(String paymentIntentId, String idempotencyKey);
}
