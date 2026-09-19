package com.electronics.store.inventory_service.messaging.message;

import java.util.Set;

public record InventoryFailedData(
        Set<UnavailableItem> unavailableItems,
        String reason) implements EventData {
}
