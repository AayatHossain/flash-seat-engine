package com.flashseat.order.api;

import com.flashseat.order.inventory.InventoryException;
import com.flashseat.order.payment.PaymentIntent;
import com.flashseat.order.payment.PaymentService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class PaymentController {

    private final PaymentService payments;

    public PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    @PostMapping("/orders/{orderId}/pay")
    public PaymentResponse pay(@PathVariable UUID orderId,
                               @RequestHeader(name = OrderController.ADMISSION_HEADER, required = false) String admissionToken) {
        if (admissionToken == null) {
            throw new InventoryException.AdmissionRejected();
        }
        PaymentIntent payment = payments.startPayment(orderId, admissionToken);
        return PaymentResponse.pending(orderId, payment);
    }
}
