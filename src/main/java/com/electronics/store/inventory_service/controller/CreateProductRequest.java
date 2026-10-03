package com.electronics.store.inventory_service.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreateProductRequest(
        @NotBlank @Size(max = 255) String sku,
        @NotBlank @Size(max = 255) String name,
        @NotNull @Positive BigDecimal price,
        @NotBlank String characteristics,
        @Size(max = 255) String description) {
}