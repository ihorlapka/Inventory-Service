# Inventory-Service — Agent Instructions

## Project Overview
Spring Boot 4.1.1 (Java 25) inventory service for an electronics store. Handles order reservation, inventory tracking, and event publishing via RabbitMQ. Uses PostgreSQL with JPA/Hibernate.

## Commands
```bash
# Build
./gradlew build

# Run tests (requires Docker for Testcontainers)
./gradlew test

# Run application
./gradlew bootRun
```

## Key Architecture
- **Entry point**: `InventoryServiceApplication.java`
- **Core processor**: `ReservationProcessor` — consumes `ORDER_CREATED` events, reserves inventory, publishes `INVENTORY_RESERVED` or `INVENTORY_FAILED`
- **Outbox pattern**: Events persisted to `outbox_events` table, published via `OutboxEventService` + `RabbitMqPublisher`
- **External dependency**: `com.electronics.store.outbox_event_publisher:outbox-event-publisher` (from GitHub Packages)

## Test Requirements
- **Docker required** for integration tests (Testcontainers: PostgreSQL + RabbitMQ)
- Unit tests in `ReservationProcessorTest` use Mockito (no Docker needed)
- Run single test: `./gradlew test --tests "com.electronics.store.inventory_service.processor.ReservationProcessorTest.processOrderCreated_successfulReservation"`

## Database
- Schema: `schema.sql` (runs on startup via `spring.jpa.hibernate.ddl-auto=validate`)
- Tables: `products`, `inventory`, `reservations`, `outbox_events`
- Enum types: `order_event_type`, `event_status`, `reservation_status`

## Configuration
- `application.yaml`: datasource, JPA, outbox settings
- GitHub Packages auth: set `gpr.user`/`gpr.key` or `USERNAME`/`TOKEN` env vars

## Conventions
- Lombok for boilerplate (compileOnly + annotationProcessor)
- UUID primary keys, JSONB for flexible fields
- Event-driven: consumes from RabbitMQ, publishes to outbox