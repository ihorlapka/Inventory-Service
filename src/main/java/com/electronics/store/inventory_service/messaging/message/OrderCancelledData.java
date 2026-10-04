package com.electronics.store.inventory_service.messaging.message;


import lombok.NonNull;

import java.util.Set;
import java.util.UUID;

public record OrderCancelledData(
        @NonNull Set<UUID> productIds,
        String reason) implements EventData {
}
