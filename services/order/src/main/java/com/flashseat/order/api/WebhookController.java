package com.flashseat.order.api;

import com.flashseat.order.payment.CheckoutSaga;
import com.flashseat.order.payment.PaymentEvent;
import com.flashseat.order.payment.WebhookSignatureVerifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * Receives payment results from the provider. Answering 2xx tells the provider to stop sending this event;
 * any error makes it deliver the event again later, which is how a half-finished checkout gets completed.
 */
@RestController
public class WebhookController {

    static final String SIGNATURE_HEADER = "Stripe-Signature";

    private final WebhookSignatureVerifier verifier;
    private final CheckoutSaga saga;
    private final JsonMapper json;

    public WebhookController(WebhookSignatureVerifier verifier, CheckoutSaga saga, JsonMapper json) {
        this.verifier = verifier;
        this.saga = saga;
        this.json = json;
    }

    @PostMapping("/payments/webhook")
    public ResponseEntity<Map<String, Boolean>> receive(@RequestHeader(name = SIGNATURE_HEADER, required = false) String signature,
                                                        @RequestBody String payload) {
        // The signature covers the exact raw bytes, so it is checked before the body is parsed.
        verifier.verify(payload, signature);
        saga.handle(PaymentEvent.parse(payload, json));
        return ResponseEntity.ok(Map.of("received", true));
    }
}
