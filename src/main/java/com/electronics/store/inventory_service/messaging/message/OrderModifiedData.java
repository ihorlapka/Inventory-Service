package com.electronics.store.inventory_service.messaging.message;

import java.util.Set;

public record OrderModifiedData(
        Set<EventItem> itemsToUpdate) implements EventData {
}
