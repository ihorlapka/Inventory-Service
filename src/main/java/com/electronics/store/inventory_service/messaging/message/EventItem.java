package com.electronics.store.inventory_service.messaging.message;

import java.math.BigDecimal;
import java.util.UUID;

public record EventItem(
        UUID id,
        UUID itemId,
        String description,
        int quantity,
        BigDecimal price,
        String itemUrl) {
}
