package com.flashseat.order.payment;

/** A payment waiting for the buyer. The client secret lets the buyer's browser finish paying with the provider. */
public record PaymentIntent(String id, String clientSecret, long amountCents, String currency) {
}
