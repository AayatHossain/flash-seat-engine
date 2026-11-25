package com.flashseat.order.idempotency;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class RequestFingerprintTest {

    private static final byte[] BODY = "{\"seats\":2}".getBytes(StandardCharsets.UTF_8);

    @Test
    void sameRequestGivesSameFingerprint() {
        assertThat(RequestFingerprint.of("POST", "/orders", BODY))
                .isEqualTo(RequestFingerprint.of("POST", "/orders", BODY.clone()));
    }

    @Test
    void anyDifferenceChangesTheFingerprint() {
        RequestFingerprint original = RequestFingerprint.of("POST", "/orders", BODY);
        byte[] otherBody = "{\"seats\":3}".getBytes(StandardCharsets.UTF_8);

        assertThat(RequestFingerprint.of("POST", "/orders", otherBody)).isNotEqualTo(original);
        assertThat(RequestFingerprint.of("POST", "/refunds", BODY)).isNotEqualTo(original);
        assertThat(RequestFingerprint.of("PUT", "/orders", BODY)).isNotEqualTo(original);
    }

    @Test
    void fingerprintIsAHexSha256() {
        assertThat(RequestFingerprint.of("POST", "/orders", BODY).value()).matches("[0-9a-f]{64}");
    }
}
