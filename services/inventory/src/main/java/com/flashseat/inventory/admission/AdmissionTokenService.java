package com.flashseat.inventory.admission;

import com.flashseat.inventory.admission.AdmissionCheck.Admitted;
import com.flashseat.inventory.admission.AdmissionCheck.Reason;
import com.flashseat.inventory.admission.AdmissionCheck.Rejected;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Base64;

/**
 * Issues and checks the tokens the waiting room hands out when it lets a user in.
 * A token looks like {@code <payload>.<signature>}, both Base64URL encoded, where the payload is
 * {@code userId:eventId:expiresAtEpochSeconds} and the signature is an HMAC-SHA256 of the payload.
 * Any service replica with the same secret can check a token without a database lookup.
 */
public class AdmissionTokenService {

    private static final String ALGORITHM = "HmacSHA256";
    private static final int MIN_KEY_BYTES = 32;
    private static final String FIELD_SEPARATOR = ":";

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final SecretKeySpec key;
    private final Duration validity;
    private final InstantSource clock;

    public AdmissionTokenService(byte[] secret, Duration validity, InstantSource clock) {
        if (secret.length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException("secret must be at least " + MIN_KEY_BYTES + " bytes");
        }
        this.key = new SecretKeySpec(secret, ALGORITHM);
        this.validity = validity;
        this.clock = clock;
    }

    public String issue(String userId, String eventId) {
        requireNoSeparator(userId, "userId");
        requireNoSeparator(eventId, "eventId");

        Instant expiresAt = clock.instant().plus(validity);
        String payload = String.join(FIELD_SEPARATOR, userId, eventId, String.valueOf(expiresAt.getEpochSecond()));
        String encodedPayload = ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8));

        return encodedPayload + "." + ENCODER.encodeToString(sign(encodedPayload));
    }

    public AdmissionCheck verify(String token, String expectedEventId) {
        if (token == null) {
            return new Rejected(Reason.MALFORMED);
        }
        String[] parts = token.split("\\.");
        if (parts.length != 2) {
            return new Rejected(Reason.MALFORMED);
        }
        String encodedPayload = parts[0];

        byte[] givenSignature;
        String[] fields;
        try {
            givenSignature = DECODER.decode(parts[1]);
            fields = new String(DECODER.decode(encodedPayload), StandardCharsets.UTF_8).split(FIELD_SEPARATOR);
        } catch (IllegalArgumentException notBase64) {
            return new Rejected(Reason.MALFORMED);
        }

        // Check the signature before trusting anything inside the payload.
        // isEqual() takes the same time whether the first or the last byte differs.
        if (!MessageDigest.isEqual(sign(encodedPayload), givenSignature)) {
            return new Rejected(Reason.BAD_SIGNATURE);
        }
        if (fields.length != 3) {
            return new Rejected(Reason.MALFORMED);
        }

        AdmissionToken admission = new AdmissionToken(fields[0], fields[1], Instant.ofEpochSecond(Long.parseLong(fields[2])));

        if (!clock.instant().isBefore(admission.expiresAt())) {
            return new Rejected(Reason.EXPIRED);
        }
        if (!admission.eventId().equals(expectedEventId)) {
            return new Rejected(Reason.WRONG_EVENT);
        }
        return new Admitted(admission);
    }

    private byte[] sign(String encodedPayload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal(encodedPayload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }

    private static void requireNoSeparator(String value, String name) {
        if (value == null || value.isBlank() || value.contains(FIELD_SEPARATOR)) {
            throw new IllegalArgumentException(name + " must be non-blank and must not contain '" + FIELD_SEPARATOR + "'");
        }
    }
}
