package com.electronics.store.inventory_service.messaging.message;

import java.util.UUID;

public record ReservedItem(
        UUID itemId,
        int quantity) {
}
