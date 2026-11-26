package com.flashseat.order.api;

import com.flashseat.order.payment.FakePaymentGateway;
import com.flashseat.order.payment.PaymentGateway;
import com.flashseat.order.payment.PaymentGatewayException;
import com.flashseat.order.payment.PaymentIntent;
import com.flashseat.order.payment.PaymentRequest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Test doubles for everything outside the order service: the inventory service and the payment provider. */
@TestConfiguration(proxyBeanMethods = false)
class CheckoutTestBeans {

    @Bean
    @Primary
    FakeInventoryClient fakeInventoryClient() {
        return new FakeInventoryClient();
    }

    @Bean
    @Primary
    SwitchablePaymentGateway switchablePaymentGateway() {
        return new SwitchablePaymentGateway();
    }

    /** The fake gateway, plus a switch to simulate the provider being down. */
    static class SwitchablePaymentGateway implements PaymentGateway {

        private final FakePaymentGateway delegate = new FakePaymentGateway();
        private volatile boolean down;

        @Override
        public PaymentIntent createPayment(PaymentRequest request) {
            if (down) {
                throw new PaymentGatewayException("Simulated outage", null);
            }
            return delegate.createPayment(request);
        }

        void setDown(boolean down) {
            this.down = down;
        }

        int paymentCount() {
            return delegate.paymentCount();
        }
    }
}
