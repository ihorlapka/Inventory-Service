package com.electronics.store.inventory_service.messaging.message;


import com.electronics.store.inventory_service.persistence.model.enums.Currency;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

public record OrderCreatedData(
        UUID customerId,
        Currency currency,
        BigDecimal totalPrice,
        Set<EventItem> items) implements EventData {
}
