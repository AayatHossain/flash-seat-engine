package com.flashseat.order.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 of the request, so we can tell "the same request retried" apart from
 * "a different request that reuses the same idempotency key".
 */
public record RequestFingerprint(String value) {

    public static RequestFingerprint of(String method, String path, byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((method + " " + path + "\n").getBytes(StandardCharsets.UTF_8));
            digest.update(body);
            return new RequestFingerprint(HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
