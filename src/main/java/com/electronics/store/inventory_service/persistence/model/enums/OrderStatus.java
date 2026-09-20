package com.electronics.store.inventory_service.persistence.model.enums;

public enum OrderStatus {

    PENDING,
    RESERVED,
    RESERVATION_FAILED,
    PENDING_PAYMENT,
    PAID,
    PAYMENT_STUCK,
    SHIPPED,
    DELIVERED,
    DELIVERY_FAILED,
    CANCELLED
}
