package com.flashseat.inventory.admission;

import java.time.Instant;

public record AdmissionToken(String userId, String eventId, Instant expiresAt) {
}
