package com.flashseat.order.api;

import com.flashseat.order.inventory.InventoryClient;
import com.flashseat.order.inventory.InventoryException;
import com.flashseat.order.inventory.InventoryHold;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Stands in for the inventory service: tests decide what each hold looks like, or which error comes back. */
class FakeInventoryClient implements InventoryClient {

    private final Map<UUID, InventoryHold> holds = new ConcurrentHashMap<>();
    private final Map<UUID, RuntimeException> failures = new ConcurrentHashMap<>();
    private final Map<UUID, ConfirmResult> confirmResults = new ConcurrentHashMap<>();
    private final Map<UUID, RuntimeException> confirmFailures = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicInteger> confirmCalls = new ConcurrentHashMap<>();

    void add(InventoryHold hold) {
        holds.put(hold.holdId(), hold);
    }

    void failWith(UUID holdId, RuntimeException error) {
        failures.put(holdId, error);
    }

    void confirmWill(UUID holdId, ConfirmResult result) {
        confirmFailures.remove(holdId);
        confirmResults.put(holdId, result);
    }

    void confirmFailsWith(UUID holdId, RuntimeException error) {
        confirmFailures.put(holdId, error);
    }

    void confirmWorksAgain(UUID holdId) {
        confirmFailures.remove(holdId);
    }

    int confirmCalls(UUID holdId) {
        return confirmCalls.getOrDefault(holdId, new AtomicInteger()).get();
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

    @Override
    public ConfirmResult confirmHold(UUID eventId, UUID holdId) {
        confirmCalls.computeIfAbsent(holdId, id -> new AtomicInteger()).incrementAndGet();
        if (confirmFailures.containsKey(holdId)) {
            throw confirmFailures.get(holdId);
        }
        return confirmResults.getOrDefault(holdId, ConfirmResult.CONFIRMED);
    }
}
