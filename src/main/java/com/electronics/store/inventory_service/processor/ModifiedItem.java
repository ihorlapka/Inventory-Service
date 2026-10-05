package com.electronics.store.inventory_service.processor;

import lombok.NonNull;

import java.util.UUID;

public record ModifiedItem(
        @NonNull UUID itemId,
        @NonNull ModificationState state,
        int newQuantity) {
}
