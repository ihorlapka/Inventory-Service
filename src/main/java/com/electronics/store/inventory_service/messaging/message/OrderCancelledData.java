package com.electronics.store.inventory_service.messaging.message;

public record OrderCancelledData(
        String reason) implements EventData {
}
