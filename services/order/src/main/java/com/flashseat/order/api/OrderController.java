package com.flashseat.order.api;

import com.flashseat.order.domain.Order;
import com.flashseat.order.domain.OrderService;
import com.flashseat.order.idempotency.IdempotencyOutcome;
import com.flashseat.order.idempotency.IdempotencyOutcome.Executed;
import com.flashseat.order.idempotency.IdempotencyOutcome.KeyReused;
import com.flashseat.order.idempotency.IdempotencyOutcome.Replayed;
import com.flashseat.order.idempotency.IdempotencyOutcome.StillInProgress;
import com.flashseat.order.idempotency.IdempotencyService;
import com.flashseat.order.idempotency.RequestFingerprint;
import com.flashseat.order.idempotency.StoredResponse;
import com.flashseat.order.inventory.InventoryClient;
import com.flashseat.order.inventory.InventoryException;
import com.flashseat.order.inventory.InventoryHold;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

@RestController
public class OrderController {

    static final String ADMISSION_HEADER = "Admission-Token";
    static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final OrderService orders;
    private final IdempotencyService idempotency;
    private final InventoryClient inventory;
    private final JsonMapper json;

    public OrderController(OrderService orders, IdempotencyService idempotency, InventoryClient inventory, JsonMapper json) {
        this.orders = orders;
        this.idempotency = idempotency;
        this.inventory = inventory;
        this.json = json;
    }

    @PostMapping("/orders")
    public ResponseEntity<String> createOrder(@RequestHeader(name = ADMISSION_HEADER, required = false) String admissionToken,
                                              @RequestHeader(name = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
                                              @RequestBody String body) {
        if (idempotencyKey == null) {
            throw new IllegalArgumentException(IDEMPOTENCY_HEADER + " header is required");
        }
        if (admissionToken == null) {
            throw new InventoryException.AdmissionRejected();
        }
        CreateOrderRequest request = json.readValue(body, CreateOrderRequest.class);
        request.requireComplete();

        // Inventory confirms the token and that this buyer owns the hold. Reading it is safe to repeat on every retry.
        InventoryHold hold = inventory.getHold(request.eventId(), request.holdId(), admissionToken);

        // Keys belong to one buyer, so two buyers who happen to pick the same key never collide.
        String scopedKey = hold.userId() + ":" + idempotencyKey;
        RequestFingerprint fingerprint = RequestFingerprint.of("POST", "/orders", request.canonicalBytes());

        IdempotencyOutcome outcome = idempotency.execute(scopedKey, fingerprint, () -> {
            Order order = orders.createOrder(request.eventId(), hold);
            return new StoredResponse(201, json.writeValueAsString(OrderResponse.from(order)));
        });

        return switch (outcome) {
            case Executed executed -> respond(executed.response(), false);
            case Replayed replayed -> respond(replayed.response(), true);
            case StillInProgress stillInProgress -> throw IdempotencyConflictException.stillInProgress();
            case KeyReused keyReused -> throw IdempotencyConflictException.keyReused();
        };
    }

    private static ResponseEntity<String> respond(StoredResponse response, boolean replayed) {
        return ResponseEntity.status(response.httpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .header(REPLAYED_HEADER, String.valueOf(replayed))
                .body(response.body());
    }
}
