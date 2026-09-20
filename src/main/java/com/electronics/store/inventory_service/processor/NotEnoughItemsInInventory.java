package com.electronics.store.inventory_service.processor;

public class NotEnoughItemsInInventory extends RuntimeException {
    public NotEnoughItemsInInventory(String message) {
        super(message);
    }
}
