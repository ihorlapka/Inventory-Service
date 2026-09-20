package com.electronics.store.inventory_service.messaging.message;

import com.electronics.store.inventory_service.persistence.model.enums.OrderEventType;
import com.electronics.store.inventory_service.persistence.model.enums.OrderStatus;
import lombok.NonNull;

import java.time.OffsetDateTime;
import java.util.UUID;

public record MessageEventOut(
        @NonNull UUID eventId,
        @NonNull OrderEventType eventType,
        @NonNull UUID orderId,
        @NonNull OrderStatus orderStatus,
        @NonNull OffsetDateTime createdAt,
        @NonNull EventData eventData) {
}
