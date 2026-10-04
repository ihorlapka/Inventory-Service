package com.electronics.store.inventory_service.messaging.message;

import lombok.NonNull;

import java.math.BigDecimal;
import java.util.UUID;

public record ReservedItem(
        @NonNull UUID itemId,
        int quantity,
        @NonNull BigDecimal price,
        @NonNull String description,
        @NonNull String itemUrl) {
}
