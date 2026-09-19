package com.electronics.store.inventory_service.messaging.message;

public sealed interface EventData permits OrderCreatedData, InventoryReservedData, InventoryFailedData {
}
