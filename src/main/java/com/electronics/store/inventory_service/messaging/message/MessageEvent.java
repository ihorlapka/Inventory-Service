package com.electronics.store.inventory_service.messaging.message;

import com.electronics.store.inventory_service.persistence.model.enums.EventType;
import com.electronics.store.inventory_service.persistence.model.enums.OrderStatus;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.NonNull;

import java.time.OffsetDateTime;
import java.util.UUID;

public record MessageEvent(
        @NonNull UUID eventId,
        @NonNull EventType eventType,
        @NonNull UUID orderId,
        @NonNull OrderStatus orderStatus,
        @NonNull OffsetDateTime createdAt,
        @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXTERNAL_PROPERTY, property = "eventType")
        @JsonSubTypes({
                @JsonSubTypes.Type(value = OrderCreatedData.class, name = "ORDER_CREATED"),
                @JsonSubTypes.Type(value = InventoryReservedData.class, name = "INVENTORY_RESERVED"),
                @JsonSubTypes.Type(value = InventoryFailedData.class, name = "INVENTORY_FAILED")
        })
        @NonNull EventData eventData) {
}
