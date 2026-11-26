package com.flashseat.order.payment;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The parts of a provider webhook that checkout needs. The JSON shape follows Stripe's event object. */
public record PaymentEvent(String eventId, Type type, String paymentIntentId) {

    public enum Type {
        SUCCEEDED,
        FAILED,
        OTHER
    }

    public static PaymentEvent parse(String payload, JsonMapper json) {
        JsonNode root = json.readTree(payload);
        String eventId = root.path("id").asString(null);
        String paymentIntentId = root.path("data").path("object").path("id").asString(null);
        if (eventId == null || paymentIntentId == null) {
            throw new IllegalArgumentException("Webhook payload is missing the event id or the payment id");
        }
        Type type = switch (root.path("type").asString("")) {
            case "payment_intent.succeeded" -> Type.SUCCEEDED;
            case "payment_intent.payment_failed" -> Type.FAILED;
            default -> Type.OTHER;
        };
        return new PaymentEvent(eventId, type, paymentIntentId);
    }
}
