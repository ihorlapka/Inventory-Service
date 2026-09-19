package com.electronics.store.inventory_service.messaging.message;

import java.util.Set;

public record InventoryReservedData(
        Set<ReservedItem> reservedItems) implements EventData {
}
