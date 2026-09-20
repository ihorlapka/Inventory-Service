package com.electronics.store.inventory_service.processor;

public class NoInventoryForProductException extends RuntimeException {
    public NoInventoryForProductException(String message) {
        super(message);
    }
}
