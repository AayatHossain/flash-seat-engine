package com.flashseat.order.idempotency;

public record StoredResponse(int httpStatus, String body) {
}
