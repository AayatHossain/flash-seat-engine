package com.flashseat.order.domain;

import java.util.UUID;

public record PaymentStartedEvent(UUID orderId, String paymentIntentId, long amountCents, String currency) {
}
