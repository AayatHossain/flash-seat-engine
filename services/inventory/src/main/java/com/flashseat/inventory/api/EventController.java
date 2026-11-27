package com.flashseat.inventory.api;

import com.flashseat.inventory.catalog.EventPage;
import com.flashseat.inventory.catalog.EventPageCache;
import com.flashseat.inventory.catalog.EventPageProperties;
import com.flashseat.inventory.waitingroom.WaitingRoomException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class EventController {

    private final EventPageCache pages;
    private final CacheControl cacheControl;

    public EventController(EventPageCache pages, EventPageProperties properties) {
        this.pages = pages;
        // Lets a CDN or the browser reuse the page for as long as our own cache would.
        this.cacheControl = CacheControl.maxAge(properties.ttl()).cachePublic();
    }

    @GetMapping("/events/{eventId}")
    public ResponseEntity<EventPage> eventPage(@PathVariable UUID eventId) {
        EventPage page = pages.get(eventId).orElseThrow(() -> new WaitingRoomException.EventNotFound(eventId));
        return ResponseEntity.ok().cacheControl(cacheControl).body(page);
    }
}
