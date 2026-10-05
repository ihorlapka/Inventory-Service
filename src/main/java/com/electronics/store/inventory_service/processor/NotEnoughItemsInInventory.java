package com.electronics.store.inventory_service.processor;

import lombok.Getter;

import java.util.UUID;

public class NotEnoughItemsInInventory extends RuntimeException {

    @Getter
    private final UUID productId;

    public NotEnoughItemsInInventory(String message, UUID productId) {
        super(message + " " + productId);
        this.productId = productId;
    }
}
