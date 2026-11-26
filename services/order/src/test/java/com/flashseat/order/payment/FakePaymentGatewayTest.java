package com.flashseat.order.payment;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FakePaymentGatewayTest {

    private final FakePaymentGateway gateway = new FakePaymentGateway();

    @Test
    void sameIdempotencyKeyReturnsTheSamePayment() {
        PaymentRequest request = new PaymentRequest(UUID.randomUUID(), 5_000, "USD", "order-1");

        PaymentIntent first = gateway.createPayment(request);
        PaymentIntent again = gateway.createPayment(request);

        assertThat(again).isEqualTo(first);
        assertThat(first.id()).startsWith("pi_");
        assertThat(first.clientSecret()).startsWith(first.id() + "_secret_");
        assertThat(gateway.paymentCount()).isEqualTo(1);
    }

    @Test
    void differentKeysCreateDifferentPayments() {
        PaymentIntent a = gateway.createPayment(new PaymentRequest(UUID.randomUUID(), 5_000, "USD", "order-1"));
        PaymentIntent b = gateway.createPayment(new PaymentRequest(UUID.randomUUID(), 5_000, "USD", "order-2"));

        assertThat(a.id()).isNotEqualTo(b.id());
    }

    @Test
    void reusingAKeyWithDifferentParametersIsRefused() {
        gateway.createPayment(new PaymentRequest(UUID.randomUUID(), 5_000, "USD", "order-1"));

        assertThatThrownBy(() -> gateway.createPayment(new PaymentRequest(UUID.randomUUID(), 9_999, "USD", "order-1")))
                .isInstanceOf(PaymentGatewayException.class);
    }
}
