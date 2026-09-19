package com.electronics.store.inventory_service.persistence.model.enums;

public enum OrderEventType {
    ORDER_CREATED,
    ORDER_CANCELLED,
    INVENTORY_RESERVED,
    INVENTORY_FAILED,
    PAYMENT_COMPLETED,
    PAYMENT_FAILED,
    SHIPMENT_CREATED,
    SHIPMENT_FAILED,
    SHIPMENT_COMPLETED
}
