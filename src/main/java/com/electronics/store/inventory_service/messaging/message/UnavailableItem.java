package com.electronics.store.inventory_service.messaging.message;

import java.util.UUID;

public record UnavailableItem(
        UUID itemId,
        int requestedQuantity,
        int availableQuantity) {
}
