package com.flashseat.order.payment;

/**
 * The payment provider as the order service sees it. Stripe is one implementation; a fake one is used
 * for local runs and tests. Implementations must honour the idempotency key: the same key always
 * returns the same payment, so a retried request can never create a second charge.
 */
public interface PaymentGateway {

    PaymentIntent createPayment(PaymentRequest request);
}
