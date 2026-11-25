package com.flashseat.inventory.admission;

public sealed interface AdmissionCheck {

    record Admitted(AdmissionToken token) implements AdmissionCheck {
    }

    record Rejected(Reason reason) implements AdmissionCheck {
    }

    enum Reason {
        MALFORMED,
        BAD_SIGNATURE,
        EXPIRED,
        WRONG_EVENT
    }
}
