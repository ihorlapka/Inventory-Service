package com.electronics.store.inventory_service;

import org.springframework.boot.SpringApplication;

public class TestInventoryServiceApplication {

	static void main(String[] args) {
		SpringApplication.from(InventoryServiceApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
