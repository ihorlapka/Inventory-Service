package com.electronics.store.inventory_service.controller;

import java.math.BigDecimal;
import java.util.UUID;

public record ProductDto(
        UUID id,
        String sku,
        String name,
        BigDecimal price,
        String characteristics,
        String imageUrl,
        String description
) {
}