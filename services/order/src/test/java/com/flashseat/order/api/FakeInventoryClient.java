package com.flashseat.order.api;

import com.flashseat.order.inventory.InventoryClient;
import com.flashseat.order.inventory.InventoryException;
import com.flashseat.order.inventory.InventoryHold;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Stands in for the inventory service: tests decide what each hold looks like, or which error comes back. */
class FakeInventoryClient implements InventoryClient {

    private final Map<UUID, InventoryHold> holds = new ConcurrentHashMap<>();
    private final Map<UUID, RuntimeException> failures = new ConcurrentHashMap<>();

    void add(InventoryHold hold) {
        holds.put(hold.holdId(), hold);
    }

    void failWith(UUID holdId, RuntimeException error) {
        failures.put(holdId, error);
    }

    @Override
    public InventoryHold getHold(UUID eventId, UUID holdId, String admissionToken) {
        if (failures.containsKey(holdId)) {
            throw failures.get(holdId);
        }
        InventoryHold hold = holds.get(holdId);
        if (hold == null) {
            throw new InventoryException.HoldNotFound(holdId);
        }
        return hold;
    }
}
