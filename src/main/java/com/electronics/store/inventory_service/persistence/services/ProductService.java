package com.electronics.store.inventory_service.persistence.services;

import com.electronics.store.inventory_service.controller.CreateProductRequest;
import com.electronics.store.inventory_service.controller.UpdateProductRequest;
import com.electronics.store.inventory_service.persistence.model.Product;
import com.electronics.store.inventory_service.persistence.repositories.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;

    @Transactional(readOnly = true)
    public List<Product> getAllProducts() {
        return productRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Product getProduct(UUID id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException(id));
    }

    @Transactional
    public Product createProduct(CreateProductRequest request) {
        Product product = new Product();
        product.setSku(request.sku());
        product.setName(request.name());
        product.setPrice(request.price());
        product.setCharacteristics(request.characteristics());
        product.setImageUrl(request.imageUrl());
        product.setDescription(request.description());
        return productRepository.save(product);
    }

    @Transactional
    public Product updateProduct(UUID id, UpdateProductRequest request) {
        Product product = getProduct(id);
        product.setSku(request.sku());
        product.setName(request.name());
        product.setPrice(request.price());
        product.setCharacteristics(request.characteristics());
        product.setDescription(request.description());
        return productRepository.save(product);
    }

    @Transactional
    public void deleteProduct(UUID id) {
        if (!productRepository.existsById(id)) {
            throw new ProductNotFoundException(id);
        }
        productRepository.deleteById(id);
    }

    public List<Product> findByProductIdIn(Set<UUID> productIds) {
        return productRepository.findByIdIn(productIds);
    }

    public static class ProductNotFoundException extends RuntimeException {
        public ProductNotFoundException(UUID id) {
            super("Product not found with id: " + id);
        }
    }
}