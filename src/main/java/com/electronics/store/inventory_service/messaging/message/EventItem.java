package com.electronics.store.inventory_service.messaging.message;

import lombok.NonNull;

import java.util.UUID;

public record EventItem(
        @NonNull UUID id,
        @NonNull UUID itemId,
        int quantity) {
}
