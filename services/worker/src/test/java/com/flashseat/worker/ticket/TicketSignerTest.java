package com.flashseat.worker.ticket;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TicketSignerTest {

    private final TicketSigner signer = new TicketSigner("a-ticket-secret-that-is-long-enough-1234");

    @Test
    void signedTicketIsGenuine() {
        String code = signer.sign(UUID.randomUUID(), UUID.randomUUID(), "A-1");

        assertThat(signer.isGenuine(code)).isTrue();
    }

    @Test
    void editedTicketIsRejected() {
        String code = signer.sign(UUID.randomUUID(), UUID.randomUUID(), "A-1");
        String otherSeat = signer.sign(UUID.randomUUID(), UUID.randomUUID(), "VIP-1");
        String forged = otherSeat.substring(0, otherSeat.indexOf('.')) + code.substring(code.indexOf('.'));

        assertThat(signer.isGenuine(forged)).isFalse();
        assertThat(signer.isGenuine("not-a-ticket")).isFalse();
        assertThat(signer.isGenuine("abc.@@@")).isFalse();
    }

    @Test
    void ticketFromAnotherSecretIsRejected() {
        String code = new TicketSigner("another-secret-that-is-long-enough-5678").sign(UUID.randomUUID(), UUID.randomUUID(), "A-1");

        assertThat(signer.isGenuine(code)).isFalse();
    }

    @Test
    void refusesShortSecrets() {
        assertThatThrownBy(() -> new TicketSigner("short")).isInstanceOf(IllegalArgumentException.class);
    }
}
