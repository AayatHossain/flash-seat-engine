package com.flashseat.order.inventory;

import java.util.UUID;

public interface InventoryClient {

    /**
     * Looks up a hold on behalf of the buyer whose admission token is given.
     * Inventory checks the token and ownership, so a returned hold is known to belong to that buyer.
     */
    InventoryHold getHold(UUID eventId, UUID holdId, String admissionToken);
}
