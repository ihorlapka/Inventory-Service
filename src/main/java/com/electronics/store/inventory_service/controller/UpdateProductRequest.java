package com.electronics.store.inventory_service.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record UpdateProductRequest(
        @NotBlank @Size(max = 255) String sku,
        @NotBlank @Size(max = 255) String name,
        @NotNull @Positive BigDecimal price,
        @NotBlank String characteristics,
        @Size(max = 255) String description
) {
    public String getSku() {
        return sku;
    }
    
    public String getName() {
        return name;
    }
    
    public BigDecimal getPrice() {
        return price;
    }
    
    public String getCharacteristics() {
        return characteristics;
    }
    
    public String getDescription() {
        return description;
    }
}