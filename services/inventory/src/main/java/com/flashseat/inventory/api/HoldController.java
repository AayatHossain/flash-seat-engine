package com.flashseat.inventory.api;

import com.flashseat.inventory.admission.AdmissionCheck.Admitted;
import com.flashseat.inventory.admission.AdmissionCheck.Rejected;
import com.flashseat.inventory.admission.AdmissionTokenService;
import com.flashseat.inventory.hold.Hold;
import com.flashseat.inventory.hold.HoldService;
import com.flashseat.inventory.hold.HoldStateException;
import com.flashseat.inventory.hold.HoldStateException.Problem;
import com.flashseat.inventory.ratelimit.InMemoryRateLimiter;
import com.flashseat.inventory.ratelimit.RateLimitDecision;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/events/{eventId}/holds")
public class HoldController {

    static final String ADMISSION_HEADER = "Admission-Token";

    private final HoldService holds;
    private final AdmissionTokenService admissions;
    private final InMemoryRateLimiter rateLimiter;

    public HoldController(HoldService holds, AdmissionTokenService admissions, InMemoryRateLimiter rateLimiter) {
        this.holds = holds;
        this.admissions = admissions;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping
    public ResponseEntity<HoldResponse> placeHold(@PathVariable UUID eventId,
                                                  @RequestHeader(name = ADMISSION_HEADER, required = false) String admissionToken,
                                                  @RequestBody PlaceHoldRequest request) {
        String userId = admittedUser(eventId, admissionToken);

        Hold hold = holds.placeHold(eventId, userId, request.seatIds());

        URI location = URI.create("/events/" + eventId + "/holds/" + hold.id());
        return ResponseEntity.created(location).body(HoldResponse.from(hold));
    }

    @GetMapping("/{holdId}")
    public HoldResponse getHold(@PathVariable UUID eventId,
                                @PathVariable UUID holdId,
                                @RequestHeader(name = ADMISSION_HEADER, required = false) String admissionToken) {
        String userId = admittedUser(eventId, admissionToken);
        return HoldResponse.from(ownHold(eventId, holdId, userId));
    }

    @DeleteMapping("/{holdId}")
    public ResponseEntity<Void> releaseHold(@PathVariable UUID eventId,
                                            @PathVariable UUID holdId,
                                            @RequestHeader(name = ADMISSION_HEADER, required = false) String admissionToken) {
        String userId = admittedUser(eventId, admissionToken);
        ownHold(eventId, holdId, userId);

        holds.releaseHold(eventId, holdId);
        return ResponseEntity.noContent().build();
    }

    /** Checks the waiting-room token first (cheap, no database), then the buyer's rate limit. */
    private String admittedUser(UUID eventId, String admissionToken) {
        String userId = switch (admissions.verify(admissionToken, eventId.toString())) {
            case Admitted admitted -> admitted.token().userId();
            case Rejected rejected -> throw new AdmissionRejectedException(rejected.reason());
        };

        RateLimitDecision decision = rateLimiter.tryAcquire(userId);
        if (!decision.allowed()) {
            throw new RateLimitedException(decision.retryAfter());
        }
        return userId;
    }

    // Someone else's hold is reported as "not found", so hold IDs cannot be probed.
    private Hold ownHold(UUID eventId, UUID holdId, String userId) {
        return holds.findHold(eventId, holdId)
                .filter(hold -> hold.userId().equals(userId))
                .orElseThrow(() -> new HoldStateException(holdId, Problem.NOT_FOUND));
    }
}
