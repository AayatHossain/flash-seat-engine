package com.flashseat.order.inventory;

import java.util.UUID;

public interface InventoryClient {

    /**
     * Looks up a hold on behalf of the buyer whose admission token is given.
     * Inventory checks the token and ownership, so a returned hold is known to belong to that buyer.
     */
    InventoryHold getHold(UUID eventId, UUID holdId, String admissionToken);

    /** Turns held seats into sold seats. Safe to call again for the same hold. */
    ConfirmResult confirmHold(UUID eventId, UUID holdId);

    enum ConfirmResult {
        CONFIRMED,
        /** The hold ran out of time before payment arrived; the seats may already belong to someone else. */
        EXPIRED,
        /** The hold was released or does not exist, so there are no seats to sell. */
        NOT_ACTIVE
    }
}
