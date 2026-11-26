package com.flashseat.order.payment;

import java.util.UUID;

public record PaymentRequest(UUID orderId, long amountCents, String currency, String idempotencyKey) {
}
