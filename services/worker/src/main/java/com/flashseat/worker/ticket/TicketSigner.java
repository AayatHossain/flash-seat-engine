package com.flashseat.worker.ticket;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;

/**
 * Produces the code printed in a ticket's QR image: {@code <payload>.<signature>}, both Base64URL,
 * where the payload is {@code ticketId|eventId|seatId}. A scanner at the venue door holding the same
 * secret can check a ticket offline; a forged or edited ticket fails the signature check.
 */
public class TicketSigner {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final SecretKeySpec key;

    public TicketSigner(String secret) {
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("Ticket secret must be at least 32 bytes");
        }
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    public String sign(UUID ticketId, UUID eventId, String seatId) {
        String payload = ENCODER.encodeToString((ticketId + "|" + eventId + "|" + seatId).getBytes(StandardCharsets.UTF_8));
        return payload + "." + ENCODER.encodeToString(hmac(payload));
    }

    public boolean isGenuine(String code) {
        String[] parts = code.split("\\.");
        if (parts.length != 2) {
            return false;
        }
        try {
            return MessageDigest.isEqual(hmac(parts[0]), DECODER.decode(parts[1]));
        } catch (IllegalArgumentException notBase64) {
            return false;
        }
    }

    private byte[] hmac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }
}
