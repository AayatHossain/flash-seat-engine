package com.flashseat.inventory.api;

import java.util.List;

public record PlaceHoldRequest(List<String> seatIds) {
}
