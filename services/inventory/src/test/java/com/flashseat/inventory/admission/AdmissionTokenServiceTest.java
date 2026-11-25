package com.flashseat.inventory.admission;

import com.flashseat.inventory.admission.AdmissionCheck.Admitted;
import com.flashseat.inventory.admission.AdmissionCheck.Reason;
import com.flashseat.inventory.admission.AdmissionCheck.Rejected;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdmissionTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-12-01T10:00:00Z");
    private static final Duration VALIDITY = Duration.ofMinutes(10);
    private static final byte[] SECRET = "a-test-secret-that-is-long-enough-1234".getBytes(StandardCharsets.UTF_8);

    private final AdmissionTokenService service = serviceAt(NOW);

    @Test
    void issuedTokenIsAcceptedForItsEvent() {
        String token = service.issue("user-1", "event-42");

        AdmissionCheck check = service.verify(token, "event-42");

        assertThat(check).isInstanceOf(Admitted.class);
        AdmissionToken admission = ((Admitted) check).token();
        assertThat(admission.userId()).isEqualTo("user-1");
        assertThat(admission.eventId()).isEqualTo("event-42");
        assertThat(admission.expiresAt()).isEqualTo(NOW.plus(VALIDITY));
    }

    @Test
    void tokenForAnotherEventIsRejected() {
        String token = service.issue("user-1", "event-42");

        assertThat(service.verify(token, "event-99")).isEqualTo(new Rejected(Reason.WRONG_EVENT));
    }

    @Test
    void tokenIsRejectedOnceItExpires() {
        String token = service.issue("user-1", "event-42");

        assertThat(serviceAt(NOW.plus(VALIDITY).minusSeconds(1)).verify(token, "event-42")).isInstanceOf(Admitted.class);
        assertThat(serviceAt(NOW.plus(VALIDITY)).verify(token, "event-42")).isEqualTo(new Rejected(Reason.EXPIRED));
    }

    @Test
    void changingThePayloadBreaksTheSignature() {
        String token = service.issue("user-1", "event-42");
        String signature = token.substring(token.indexOf('.') + 1);
        String forgedPayload = base64("someone-else:event-42:" + NOW.plus(Duration.ofDays(1)).getEpochSecond());

        AdmissionCheck check = service.verify(forgedPayload + "." + signature, "event-42");

        assertThat(check).isEqualTo(new Rejected(Reason.BAD_SIGNATURE));
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() {
        byte[] otherSecret = "a-different-secret-that-is-long-enough".getBytes(StandardCharsets.UTF_8);
        AdmissionTokenService otherService = new AdmissionTokenService(otherSecret, VALIDITY, InstantSource.fixed(NOW));
        String token = otherService.issue("user-1", "event-42");

        assertThat(service.verify(token, "event-42")).isEqualTo(new Rejected(Reason.BAD_SIGNATURE));
    }

    @Test
    void garbageIsRejectedAsMalformed() {
        Rejected malformed = new Rejected(Reason.MALFORMED);

        assertThat(service.verify(null, "event-42")).isEqualTo(malformed);
        assertThat(service.verify("", "event-42")).isEqualTo(malformed);
        assertThat(service.verify("no-dot-here", "event-42")).isEqualTo(malformed);
        assertThat(service.verify("a.b.c", "event-42")).isEqualTo(malformed);
        assertThat(service.verify("not base64!.also not!", "event-42")).isEqualTo(malformed);
    }

    @Test
    void refusesShortSecrets() {
        byte[] shortSecret = "too-short".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> new AdmissionTokenService(shortSecret, VALIDITY, InstantSource.fixed(NOW)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesIdsThatWouldBreakThePayloadFormat() {
        assertThatThrownBy(() -> service.issue("user:1", "event-42")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.issue("user-1", " ")).isInstanceOf(IllegalArgumentException.class);
    }

    private static AdmissionTokenService serviceAt(Instant now) {
        return new AdmissionTokenService(SECRET, VALIDITY, InstantSource.fixed(now));
    }

    private static String base64(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
}
