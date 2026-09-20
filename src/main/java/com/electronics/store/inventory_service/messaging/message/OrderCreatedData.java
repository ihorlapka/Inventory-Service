package com.electronics.store.inventory_service.messaging.message;

import com.electronics.store.inventory_service.persistence.model.enums.Currency;
import lombok.NonNull;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

public record OrderCreatedData(
        @NonNull UUID customerId,
        @NonNull Currency currency,
        @NonNull BigDecimal totalPrice,
        @NonNull Set<EventItem> items) implements EventData {
}
