package com.electronics.store.inventory_service.persistence.repositories;

import com.electronics.store.inventory_service.persistence.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Repository
public interface ProductRepository extends JpaRepository<Product, UUID> {

    List<Product> findByProductIdIn(Set<UUID> productIds);
}
