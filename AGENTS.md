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

## MVC Testing Patterns (Spring Boot 4.1.1)
- Use `@WebMvcTest(Controller.class)` with `@MockitoBean` for service mocks
- `@WebMvcTest` auto-configures MockMvc, Jackson ObjectMapper, and validation
- `@MockitoBean` (Spring Boot 4.1.1+) replaces `@MockBean`
- Add `@ControllerAdvice` (GlobalErrorHandler) to test context via `@Import(GlobalErrorHandler.class)` or `@WebMvcTest(controllers = {Controller.class, GlobalErrorHandler.class})`
- Request DTOs: use records with Jakarta Bean Validation (`@NotBlank`, `@Size`, `@Positive`, `@NotNull`) — avoid `@NonNull` (Lombok) as it breaks Jackson deserialization before validation
- Run: `./gradlew test --tests "com.electronics.store.inventory_service.controller.ProductControllerMvcTest"`

## Test Dependencies
- `spring-boot-starter-webmvc-test` + `spring-boot-starter-test` (provides MockMvc, Mockito, AssertJ, JSON path)
- Testcontainers only for integration tests (`@SpringBootTest` + `@Testcontainers`)