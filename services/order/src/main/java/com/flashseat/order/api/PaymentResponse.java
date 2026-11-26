package com.flashseat.order.api;

import com.flashseat.order.payment.PaymentIntent;

import java.util.UUID;

public record PaymentResponse(UUID orderId, String status, String paymentIntentId, String clientSecret,
                              long amountCents, String currency) {

    static PaymentResponse pending(UUID orderId, PaymentIntent payment) {
        return new PaymentResponse(orderId, "PAYMENT_PENDING", payment.id(), payment.clientSecret(),
                payment.amountCents(), payment.currency());
    }
}
