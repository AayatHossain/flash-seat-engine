package com.flashseat.order.payment;

import com.flashseat.order.payment.WebhookSignatureVerifier.InvalidSignatureException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookSignatureVerifierTest {

    private static final Instant NOW = Instant.parse("2026-12-01T10:00:00Z");
    private static final String PAYLOAD = "{\"id\":\"evt_1\",\"type\":\"payment_intent.succeeded\"}";

    private final WebhookSignatureVerifier verifier =
            new WebhookSignatureVerifier("whsec_test_secret", Duration.ofMinutes(5), InstantSource.fixed(NOW));

    @Test
    void acceptsAFreshCorrectSignature() {
        String header = verifier.signatureHeaderFor(PAYLOAD, NOW.minusSeconds(30));

        assertThatCode(() -> verifier.verify(PAYLOAD, header)).doesNotThrowAnyException();
    }

    @Test
    void rejectsAChangedBody() {
        String header = verifier.signatureHeaderFor(PAYLOAD, NOW);

        assertThatThrownBy(() -> verifier.verify(PAYLOAD.replace("succeeded", "failed"), header))
                .isInstanceOf(InvalidSignatureException.class);
    }

    @Test
    void rejectsASignatureMadeWithAnotherSecret() {
        WebhookSignatureVerifier attacker =
                new WebhookSignatureVerifier("whsec_guessed", Duration.ofMinutes(5), InstantSource.fixed(NOW));

        assertThatThrownBy(() -> verifier.verify(PAYLOAD, attacker.signatureHeaderFor(PAYLOAD, NOW)))
                .isInstanceOf(InvalidSignatureException.class);
    }

    @Test
    void rejectsAnOldSignatureSoCapturedWebhooksCannotBeReplayed() {
        String header = verifier.signatureHeaderFor(PAYLOAD, NOW.minus(Duration.ofMinutes(6)));

        assertThatThrownBy(() -> verifier.verify(PAYLOAD, header))
                .isInstanceOf(InvalidSignatureException.class)
                .hasMessageContaining("window");
    }

    @Test
    void acceptsWhenAnyOfSeveralSignaturesMatches() {
        String valid = verifier.signatureHeaderFor(PAYLOAD, NOW);
        String header = valid + ",v1=" + "00".repeat(32);

        assertThatCode(() -> verifier.verify(PAYLOAD, header)).doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingOrBrokenHeaders() {
        assertThatThrownBy(() -> verifier.verify(PAYLOAD, null)).isInstanceOf(InvalidSignatureException.class);
        assertThatThrownBy(() -> verifier.verify(PAYLOAD, "garbage")).isInstanceOf(InvalidSignatureException.class);
        assertThatThrownBy(() -> verifier.verify(PAYLOAD, "t=abc,v1=zz")).isInstanceOf(InvalidSignatureException.class);
    }
}
