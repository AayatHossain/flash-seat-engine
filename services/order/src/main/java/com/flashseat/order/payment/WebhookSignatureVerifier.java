package com.flashseat.order.payment;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Checks webhook signatures in Stripe's format: {@code t=<unix seconds>,v1=<hex HMAC-SHA256 of "t.body">}.
 * The timestamp is part of what is signed, and old timestamps are refused, so a captured webhook
 * cannot be replayed later.
 */
public class WebhookSignatureVerifier {

    private final SecretKeySpec key;
    private final Duration tolerance;
    private final InstantSource clock;

    public WebhookSignatureVerifier(String secret, Duration tolerance, InstantSource clock) {
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.tolerance = tolerance;
        this.clock = clock;
    }

    public void verify(String payload, String signatureHeader) {
        if (signatureHeader == null || signatureHeader.isBlank()) {
            throw new InvalidSignatureException("Missing signature header");
        }
        Long timestamp = null;
        List<String> signatures = new ArrayList<>();
        for (String part : signatureHeader.split(",")) {
            String[] keyValue = part.trim().split("=", 2);
            if (keyValue.length != 2) {
                continue;
            }
            switch (keyValue[0]) {
                case "t" -> timestamp = parseTimestamp(keyValue[1]);
                case "v1" -> signatures.add(keyValue[1]);
                default -> { }
            }
        }
        if (timestamp == null || signatures.isEmpty()) {
            throw new InvalidSignatureException("Signature header has no timestamp or no v1 signature");
        }

        Duration age = Duration.between(Instant.ofEpochSecond(timestamp), clock.instant()).abs();
        if (age.compareTo(tolerance) > 0) {
            throw new InvalidSignatureException("Signature timestamp is outside the allowed window");
        }

        byte[] expected = sign(timestamp + "." + payload);
        // Providers may send several v1 signatures while rotating secrets; one match is enough.
        for (String signature : signatures) {
            if (MessageDigest.isEqual(expected, hexOrEmpty(signature))) {
                return;
            }
        }
        throw new InvalidSignatureException("Signature does not match");
    }

    public String signatureHeaderFor(String payload, Instant at) {
        long timestamp = at.getEpochSecond();
        return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(sign(timestamp + "." + payload));
    }

    private byte[] sign(String signedPayload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }

    private static Long parseTimestamp(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static byte[] hexOrEmpty(String value) {
        try {
            return HexFormat.of().parseHex(value);
        } catch (IllegalArgumentException e) {
            return new byte[0];
        }
    }

    public static class InvalidSignatureException extends RuntimeException {
        public InvalidSignatureException(String message) {
            super(message);
        }
    }
}
