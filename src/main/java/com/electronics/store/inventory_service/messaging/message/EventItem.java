package com.electronics.store.inventory_service.messaging.message;

import lombok.NonNull;

import java.math.BigDecimal;
import java.util.UUID;

public record EventItem(
        @NonNull UUID id,
        @NonNull UUID itemId,
        @NonNull String description,
        int quantity,
        @NonNull BigDecimal price,
        @NonNull String itemUrl) {
}
