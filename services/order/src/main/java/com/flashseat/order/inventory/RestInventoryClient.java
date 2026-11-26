package com.flashseat.order.inventory;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.UUID;

public class RestInventoryClient implements InventoryClient {

    static final String ADMISSION_HEADER = "Admission-Token";

    private final RestClient rest;

    public RestInventoryClient(RestClient rest) {
        this.rest = rest;
    }

    @Override
    public InventoryHold getHold(UUID eventId, UUID holdId, String admissionToken) {
        try {
            return rest.get()
                    .uri("/events/{eventId}/holds/{holdId}", eventId, holdId)
                    .header(ADMISSION_HEADER, admissionToken)
                    .retrieve()
                    .onStatus(status -> status.value() == 404, (request, response) -> {
                        throw new InventoryException.HoldNotFound(holdId);
                    })
                    .onStatus(status -> status.value() == 403, (request, response) -> {
                        throw new InventoryException.AdmissionRejected();
                    })
                    .onStatus(status -> status.value() == 429, (request, response) -> {
                        throw new InventoryException.RateLimited(retryAfter(response.getHeaders()));
                    })
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        throw new InventoryException.Unavailable("Inventory answered " + response.getStatusCode(), null);
                    })
                    .body(InventoryHold.class);
        } catch (ResourceAccessException e) {
            // Connection refused or timed out: inventory is down or too slow, not a problem with the request.
            throw new InventoryException.Unavailable("Inventory is unreachable", e);
        }
    }

    @Override
    public ConfirmResult confirmHold(UUID eventId, UUID holdId) {
        try {
            return rest.post()
                    .uri("/internal/events/{eventId}/holds/{holdId}/confirm", eventId, holdId)
                    .exchange((request, response) -> switch (response.getStatusCode().value()) {
                        case 204 -> ConfirmResult.CONFIRMED;
                        case 410 -> ConfirmResult.EXPIRED;
                        case 404, 409 -> ConfirmResult.NOT_ACTIVE;
                        default -> throw new InventoryException.Unavailable(
                                "Inventory answered " + response.getStatusCode() + " to a confirm", null);
                    });
        } catch (ResourceAccessException e) {
            throw new InventoryException.Unavailable("Inventory is unreachable", e);
        }
    }

    private static Duration retryAfter(HttpHeaders headers) {
        String value = headers.getFirst(HttpHeaders.RETRY_AFTER);
        try {
            return Duration.ofSeconds(value == null ? 1 : Long.parseLong(value));
        } catch (NumberFormatException e) {
            return Duration.ofSeconds(1);
        }
    }
}
