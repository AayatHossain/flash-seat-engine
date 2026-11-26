package com.flashseat.inventory.api;

import com.flashseat.inventory.waitingroom.QueueStatus;
import com.flashseat.inventory.waitingroom.WaitingRoom;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The buyer's identity comes from the {@code User-Id} header. In the deployed system the API gateway sets it
 * after checking the buyer's login token and strips any value sent from outside, so services can trust it.
 */
@RestController
@RequestMapping("/events/{eventId}/queue")
public class WaitingRoomController {

    static final String USER_HEADER = "User-Id";

    private final WaitingRoom waitingRoom;

    public WaitingRoomController(WaitingRoom waitingRoom) {
        this.waitingRoom = waitingRoom;
    }

    @PostMapping
    public QueueStatus join(@PathVariable UUID eventId, @RequestHeader(USER_HEADER) String userId) {
        return waitingRoom.join(eventId, requireUser(userId));
    }

    @GetMapping("/me")
    public QueueStatus status(@PathVariable UUID eventId, @RequestHeader(USER_HEADER) String userId) {
        return waitingRoom.status(eventId, requireUser(userId));
    }

    private static String requireUser(String userId) {
        if (userId.isBlank() || userId.length() > 100 || userId.contains(":")) {
            throw new IllegalArgumentException("User-Id must be 1 to 100 characters and must not contain ':'");
        }
        return userId;
    }
}
