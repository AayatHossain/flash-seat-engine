package com.flashseat.inventory.api;

import com.flashseat.inventory.admission.AdmissionCheck.Reason;

class AdmissionRejectedException extends RuntimeException {

    private final Reason reason;

    AdmissionRejectedException(Reason reason) {
        super("Admission token rejected: " + reason);
        this.reason = reason;
    }

    Reason getReason() {
        return reason;
    }
}
