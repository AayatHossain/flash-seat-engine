package com.flashseat.inventory.api;

import com.flashseat.inventory.hold.HoldService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Called by the order service during checkout, never by buyers. The /internal path is not exposed
 * through the public gateway; inside the cluster it will be protected by network policy and mTLS.
 * Both calls are safe to repeat.
 */
@RestController
@RequestMapping("/internal/events/{eventId}/holds/{holdId}")
public class InternalHoldController {

    private final HoldService holds;

    public InternalHoldController(HoldService holds) {
        this.holds = holds;
    }

    @PostMapping("/confirm")
    public ResponseEntity<Void> confirm(@PathVariable UUID eventId, @PathVariable UUID holdId) {
        holds.confirmHold(eventId, holdId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/release")
    public ResponseEntity<Void> release(@PathVariable UUID eventId, @PathVariable UUID holdId) {
        holds.releaseHold(eventId, holdId);
        return ResponseEntity.noContent().build();
    }
}
