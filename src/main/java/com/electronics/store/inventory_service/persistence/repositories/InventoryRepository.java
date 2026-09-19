package com.electronics.store.inventory_service.persistence.repositories;

import com.electronics.store.inventory_service.persistence.model.Inventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface InventoryRepository extends JpaRepository<Inventory, UUID> {
}
