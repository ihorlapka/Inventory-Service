package com.electronics.store.inventory_service.integration;

import com.electronics.store.inventory_service.messaging.RabbitMqProperties;
import com.electronics.store.inventory_service.messaging.RabbitMqPublisher;
import com.electronics.store.inventory_service.messaging.message.*;
import com.electronics.store.inventory_service.persistence.model.Inventory;
import com.electronics.store.inventory_service.persistence.model.OutboxEvent;
import com.electronics.store.inventory_service.persistence.model.Product;
import com.electronics.store.inventory_service.persistence.model.Reservation;
import com.electronics.store.inventory_service.persistence.model.enums.EventType;
import com.electronics.store.inventory_service.persistence.model.enums.PublishmentStatus;
import com.electronics.store.inventory_service.persistence.model.enums.ReservationStatus;
import com.electronics.store.inventory_service.persistence.repositories.InventoryRepository;
import com.electronics.store.inventory_service.persistence.repositories.OutboxEventRepository;
import com.electronics.store.inventory_service.persistence.repositories.ProductRepository;
import com.electronics.store.inventory_service.persistence.repositories.ReservationRepository;
import com.electronics.store.outbox_event_publisher.OutboxEventHandler;
import com.electronics.store.outbox_event_publisher.OutboxEventManager;
import com.electronics.store.outbox_event_publisher.OutboxProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.electronics.store.inventory_service.persistence.model.enums.OrderStatus.CANCELLED;
import static com.electronics.store.inventory_service.persistence.model.enums.OrderStatus.MODIFIED;
import static java.math.RoundingMode.HALF_UP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@ActiveProfiles("test")
@SpringBootTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource("classpath:application-test.yaml")
class ReservationProcessorIntegrationTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:17.5"))
            .withInitScript("schema.sql");

    @Container
    static RabbitMQContainer rabbit = new RabbitMQContainer(DockerImageName.parse("rabbitmq:4.1.3-management"))
            .withExposedPorts(5672, 15672);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.rabbitmq.host", rabbit::getHost);
        registry.add("spring.rabbitmq.port", rabbit::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbit::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbit::getAdminPassword);
    }

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private OutboxEventHandler<OutboxEvent> outboxEventHandler;

    @Autowired
    private OutboxEventManager<OutboxEvent> outboxEventManager;

    @Autowired
    private OutboxProcessor<OutboxEvent> outboxProcessor;

    @Autowired
    private RabbitMqPublisher rabbitMqPublisher;

    @Autowired
    private RabbitMqProperties rabbitProps;

    private UUID productId1;
    private UUID productId2;

    @BeforeEach
    void setUp() {
        Product product1 = new Product(productId1, "prod1-sku", "lap top", BigDecimal.valueOf(1200).setScale(6, HALF_UP), "{}", "http://some.laptop.url", "description");
        Product product2 = new Product(productId2, "prod2-sku", "phone", BigDecimal.valueOf(550).setScale(6, HALF_UP), "{}", "http://some.phone.url", "description");
        productId1 = productRepository.save(product1).getId();
        productId2 = productRepository.save(product2).getId();
    }

    @AfterEach
    void tearDown() {
        reservationRepository.deleteAll();
        outboxEventRepository.deleteAll();
        inventoryRepository.deleteAll();
        productRepository.deleteAll();
    }

    private UUID newOrderId() {
        return UUID.randomUUID();
    }

    @Test
    void shouldProcessOrderCreatedAndPublishOutboxEventViaOutboxEventPublisher() {
        UUID orderId = newOrderId();
        createInventory(productId1, 10, 0);
        createInventory(productId2, 5, 0);

        MessageEvent event = createOrderEvent(orderId, productId1, 2, productId2, 3);

        sendOrderCreatedEvent(event);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<OutboxEvent> events = outboxEventRepository.findAllByOrderId(orderId);
            assertThat(events).hasSize(1);

            OutboxEvent outboxEvent = events.getFirst();
            assertThat(outboxEvent.getEventType()).isEqualTo(EventType.INVENTORY_RESERVED);
            assertThat(outboxEvent.getStatus()).isEqualTo(PublishmentStatus.PUBLISHED);
            assertThat(outboxEvent.getOrderId()).isEqualTo(orderId);
            assertThat(outboxEvent.getPayload()).isNotNull();
            assertThat(outboxEvent.getPayload()).contains("INVENTORY_RESERVED");
            assertThat(outboxEvent.getPayload()).contains("reservedItems");
        });

        List<Reservation> reservations = reservationRepository.findAllByOrderId(orderId);
        assertThat(reservations).hasSize(2);
        assertThat(reservations).allMatch(r -> r.getStatus() == ReservationStatus.RESERVED);

        List<Inventory> inventories = inventoryRepository.findAllByProductIdIn(Set.of(productId1, productId2));
        assertThat(inventories).hasSize(2);
        inventories.forEach(inventory -> {
            if (inventory.getProductId().equals(productId1)) {
                assertThat(inventory.getReservedQuantity()).isEqualTo(2);
                assertThat(inventory.getAvailableQuantity()).isEqualTo(8);
            }
            if (inventory.getProductId().equals(productId2)) {
                assertThat(inventory.getReservedQuantity()).isEqualTo(3);
                assertThat(inventory.getAvailableQuantity()).isEqualTo(2);
            }
        });

        assertThat(outboxEventHandler).isNotNull();
        assertThat(outboxEventManager).isNotNull();
        assertThat(outboxProcessor).isNotNull();
        assertThat(rabbitMqPublisher).isNotNull();
    }

    @Test
    void shouldProcessMultipleMessagesInSingleOutboxProcessorIteration() {
        UUID orderId1 = newOrderId();
        UUID orderId2 = newOrderId();
        UUID orderId3 = newOrderId();

        createInventory(productId1, 50, 0);
        createInventory(productId2, 50, 0);

        MessageEvent event1 = createOrderEvent(orderId1, productId1, 5, productId2, 3);
        MessageEvent event2 = createOrderEvent(orderId2, productId1, 2, productId2, 4);
        MessageEvent event3 = createOrderEvent(orderId3, productId1, 1, productId2, 2);

        sendOrderCreatedEvent(event1);
        sendOrderCreatedEvent(event2);
        sendOrderCreatedEvent(event3);

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            List<OutboxEvent> events = outboxEventRepository.findAllByOrderIdIn(List.of(orderId1, orderId2, orderId3));
            assertThat(events).hasSize(3);

            List<OutboxEvent> publishedEvents = events.stream()
                    .filter(e -> e.getStatus() == PublishmentStatus.PUBLISHED)
                    .toList();
            assertThat(publishedEvents).hasSize(3);
        });

        List<Reservation> reservations = reservationRepository.findAllByOrderIdIn(List.of(orderId1, orderId2, orderId3));
        assertThat(reservations).hasSize(6);

        List<Inventory> inventories = inventoryRepository.findAllByProductIdIn(Set.of(productId1, productId2));
        assertThat(inventories).hasSize(2);
        inventories.forEach(inventory -> {
            if (inventory.getProductId().equals(productId1)) {
                assertThat(inventory.getReservedQuantity()).isEqualTo(8);
                assertThat(inventory.getAvailableQuantity()).isEqualTo(42);
            }
            if (inventory.getProductId().equals(productId2)) {
                assertThat(inventory.getReservedQuantity()).isEqualTo(9);
                assertThat(inventory.getAvailableQuantity()).isEqualTo(41);
            }
        });

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            List<OutboxEvent> events = outboxEventRepository.findAllByOrderIdIn(List.of(orderId1, orderId2, orderId3));
            long publishedCount = events.stream().filter(e -> e.getStatus() == PublishmentStatus.PUBLISHED).count();
            long newCount = events.stream().filter(e -> e.getStatus() == PublishmentStatus.NEW).count();
            assertThat(publishedCount).isEqualTo(3);
            assertThat(newCount).isEqualTo(0);
        });
    }

    @Test
    void shouldCreateFailedOutboxEventWhenInventoryMissing() {
        UUID orderId = newOrderId();
        createInventory(productId1, 10, 0);

        UUID missingProductId = UUID.randomUUID();
        MessageEvent event = createOrderEvent(orderId, productId1, 2, missingProductId, 3);

        sendOrderCreatedEvent(event);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<OutboxEvent> events = outboxEventRepository.findAllByOrderId(orderId);
            assertThat(events).hasSize(1);

            OutboxEvent outboxEvent = events.getFirst();
            assertThat(outboxEvent.getEventType()).isEqualTo(EventType.INVENTORY_FAILED);
            assertThat(outboxEvent.getStatus()).isEqualTo(PublishmentStatus.PUBLISHED);
            assertThat(outboxEvent.getPayload()).contains("No records in db for requested items!");
        });

        List<Inventory> inventories = inventoryRepository.findAllByProductIdIn(Set.of(productId1, productId2));
        assertThat(inventories).hasSize(1);
        assertThat(inventories.getFirst().getReservedQuantity()).isEqualTo(0);
        assertThat(inventories.getFirst().getAvailableQuantity()).isEqualTo(10);

        assertThat(reservationRepository.findAllByOrderId(orderId)).isEmpty();
    }

    @Test
    void shouldCreateFailedOutboxEventWhenInsufficientInventory() {
        UUID orderId = newOrderId();
        createInventory(productId1, 2, 0);
        createInventory(productId2, 5, 0);

        MessageEvent event = createOrderEvent(orderId, productId1, 5, productId2, 3);

        sendOrderCreatedEvent(event);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<OutboxEvent> events = outboxEventRepository.findAllByOrderId(orderId);
            assertThat(events).hasSize(1);

            OutboxEvent outboxEvent = events.getFirst();
            assertThat(outboxEvent.getEventType()).isEqualTo(EventType.INVENTORY_FAILED);
            assertThat(outboxEvent.getStatus()).isIn(PublishmentStatus.NEW, PublishmentStatus.PUBLISHED);
            assertThat(outboxEvent.getPayload()).contains("Not enough items in inventory!");
        });

        List<Inventory> inventories = inventoryRepository.findAllByProductIdIn(Set.of(productId1, productId2));
        assertThat(inventories).hasSize(2);
        inventories.forEach(inventory -> {
            if (inventory.getProductId().equals(productId1)) {
                assertThat(inventory.getReservedQuantity()).isEqualTo(0);
                assertThat(inventory.getAvailableQuantity()).isEqualTo(2);
            }
            if (inventory.getProductId().equals(productId2)) {
                assertThat(inventory.getReservedQuantity()).isEqualTo(0);
                assertThat(inventory.getAvailableQuantity()).isEqualTo(5);
            }
        });

        assertThat(reservationRepository.findAllByOrderId(orderId)).isEmpty();
    }

    private void sendOrderEvent(MessageEvent event, String routingKey) {
        MessageProperties messageProperties = new MessageProperties();
        messageProperties.setMessageId(UUID.randomUUID().toString());
        messageProperties.setCorrelationId(UUID.randomUUID().toString());
        messageProperties.setTimestamp(new java.util.Date());
        messageProperties.setContentType("application/json");

        Message message = new Message(
                rabbitTemplate.getMessageConverter().toMessage(event, messageProperties).getBody(),
                messageProperties
        );

        rabbitTemplate.send(rabbitProps.getOrdersExchange(), routingKey, message);
    }

    private void sendOrderCreatedEvent(MessageEvent event) {
        sendOrderEvent(event, "orders.created");
    }

    private void sendOrderCancelledEvent(MessageEvent event) {
        sendOrderEvent(event, "orders.cancelled");
    }

    private void sendOrderModifiedEvent(MessageEvent event) {
        sendOrderEvent(event, "orders.modified");
    }

    private void createInventory(UUID productId, int availableQty, int reservedQty) {
        Inventory inventory = new Inventory();
        inventory.setProductId(productId);
        inventory.setAvailableQuantity(availableQty);
        inventory.setReservedQuantity(reservedQty);
        inventoryRepository.save(inventory);
    }

    private MessageEvent createOrderEvent(UUID orderId, UUID productId1, int qty1, UUID productId2, int qty2) {
        Set<EventItem> items = new HashSet<>();
        items.add(new EventItem(UUID.randomUUID(), productId1, qty1));
        items.add(new EventItem(UUID.randomUUID(), productId2, qty2));

        OrderCreatedData orderData = new OrderCreatedData(UUID.randomUUID(), com.electronics.store.inventory_service.persistence.model.enums.Currency.USD,
                new BigDecimal("275.00"), items);

        return new MessageEvent(
                UUID.randomUUID(),
                EventType.ORDER_CREATED,
                orderId,
                com.electronics.store.inventory_service.persistence.model.enums.OrderStatus.PENDING,
                OffsetDateTime.now(),
                orderData
        );
    }

    private MessageEvent createOrderEvent(UUID orderId, UUID productId1, int qty1, UUID productId2, int qty2, UUID productId3, int qty3) {
        Set<EventItem> items = new HashSet<>();
        items.add(new EventItem(UUID.randomUUID(), productId1, qty1));
        items.add(new EventItem(UUID.randomUUID(), productId2, qty2));
        items.add(new EventItem(UUID.randomUUID(), productId3, qty3));

        OrderCreatedData orderData = new OrderCreatedData(UUID.randomUUID(), com.electronics.store.inventory_service.persistence.model.enums.Currency.USD,
                new BigDecimal("275.00"), items);

        return new MessageEvent(
                UUID.randomUUID(),
                EventType.ORDER_CREATED,
                orderId,
                com.electronics.store.inventory_service.persistence.model.enums.OrderStatus.PENDING,
                OffsetDateTime.now(),
                orderData
        );
    }

    private MessageEvent createOrderCancelledEvent(UUID orderId, Set<UUID> productIds) {
        OrderCancelledData orderData = new OrderCancelledData(productIds, "Customer requested cancellation");
        return new MessageEvent(
                UUID.randomUUID(),
                EventType.ORDER_CANCELLED,
                orderId,
                CANCELLED,
                OffsetDateTime.now(),
                orderData
        );
    }

    private MessageEvent createOrderModifiedEvent(UUID orderId, Set<EventItem> itemsToUpdate) {
        OrderModifiedData orderData = new OrderModifiedData(itemsToUpdate);
        return new MessageEvent(
                UUID.randomUUID(),
                EventType.ORDER_MODIFIED,
                orderId,
                MODIFIED,
                OffsetDateTime.now(),
                orderData
        );
    }

    @Test
    void shouldProcessOrderCancelledAndReleaseReservations() {
        UUID orderId = newOrderId();
        createInventory(productId1, 10, 0);
        createInventory(productId2, 10, 0);

        MessageEvent orderEvent = createOrderEvent(orderId, productId1, 2, productId2, 3);
        sendOrderCreatedEvent(orderEvent);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<OutboxEvent> events = outboxEventRepository.findAllByOrderId(orderId);
            assertThat(events).hasSize(1);
            assertThat(events.getFirst().getEventType()).isEqualTo(EventType.INVENTORY_RESERVED);
            assertThat(events.getFirst().getStatus()).isEqualTo(PublishmentStatus.PUBLISHED);
        });

        List<Reservation> reservations = reservationRepository.findAllByOrderId(orderId);
        assertThat(reservations).hasSize(2);
        assertThat(reservations).allMatch(r -> r.getStatus() == ReservationStatus.RESERVED);

        List<Inventory> inventories1 = inventoryRepository.findAllByProductIdIn(Set.of(productId1, productId2));
        assertThat(inventories1).hasSize(2);
        inventories1.forEach(inventory -> {
            if (inventory.getProductId().equals(productId1)) {
                assertThat(inventory.getReservedQuantity()).isEqualTo(2);
                assertThat(inventory.getAvailableQuantity()).isEqualTo(8);
            }
            if (inventory.getProductId().equals(productId2)) {
                assertThat(inventory.getReservedQuantity()).isEqualTo(3);
                assertThat(inventory.getAvailableQuantity()).isEqualTo(7);
            }
        });

        MessageEvent cancelEvent = createOrderCancelledEvent(orderId, Set.of(productId1, productId2));
        sendOrderCancelledEvent(cancelEvent);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<Reservation> cancelledReservations = reservationRepository.findAllByOrderId(orderId);
            assertThat(cancelledReservations).allMatch(r -> r.getStatus() == ReservationStatus.RELEASED);
            assertThat(cancelledReservations).allMatch(r -> r.getReleasedAt() != null);
        });

        List<Inventory> inventories = inventoryRepository.findAllByProductIdIn(Set.of(productId1, productId2));
        assertThat(inventories).hasSize(2);
        inventories.forEach(inventory -> {
            if (inventory.getProductId().equals(productId1)) {
                assertThat(inventory.getReservedQuantity()).isEqualTo(0);
                assertThat(inventory.getAvailableQuantity()).isEqualTo(10);
            }
            if (inventory.getProductId().equals(productId2)) {
                assertThat(inventory.getReservedQuantity()).isEqualTo(0);
                assertThat(inventory.getAvailableQuantity()).isEqualTo(10);
            }
        });
    }

    @Test
    void shouldProcessOrderCancelledWithPartialProducts() {
        UUID orderId = newOrderId();
        createInventory(productId1, 10, 0);
        createInventory(productId2, 5, 0);

        MessageEvent orderEvent = createOrderEvent(orderId, productId1, 2, productId2, 3);
        sendOrderModifiedEvent(orderEvent);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<OutboxEvent> events = outboxEventRepository.findAllByOrderId(orderId);
            assertThat(events).hasSize(1);
            assertThat(events.getFirst().getEventType()).isEqualTo(EventType.INVENTORY_RESERVED);
        });

        List<Inventory> inventories1 = inventoryRepository.findAllByProductIdIn(Set.of(productId1, productId2));
        assertThat(inventories1).hasSize(2);
        inventories1.forEach(inventory -> {
            if (inventory.getProductId().equals(productId1)) {
                assertThat(inventory.getReservedQuantity()).isEqualTo(2);
                assertThat(inventory.getAvailableQuantity()).isEqualTo(8);
            }
            if (inventory.getProductId().equals(productId2)) {
                assertThat(inventory.getReservedQuantity()).isEqualTo(3);
                assertThat(inventory.getAvailableQuantity()).isEqualTo(2);
            }
        });

        UUID productId3 = UUID.randomUUID();
        MessageEvent cancelEvent = createOrderCancelledEvent(orderId, Set.of(productId1, productId2, productId3));
        sendOrderCancelledEvent(cancelEvent);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<Reservation> cancelledReservations = reservationRepository.findAllByOrderId(orderId);
            assertThat(cancelledReservations).allMatch(r -> r.getStatus() == ReservationStatus.RELEASED);
        });

        List<Inventory> inventories = inventoryRepository.findAllByProductIdIn(Set.of(productId1, productId2));
        assertThat(inventories).hasSize(2);
        inventories.forEach(inventory -> {
            if (inventory.getProductId().equals(productId1)) {
                assertThat(inventory.getReservedQuantity()).isEqualTo(0);
                assertThat(inventory.getAvailableQuantity()).isEqualTo(10);
            }
            if (inventory.getProductId().equals(productId2)) {
                assertThat(inventory.getReservedQuantity()).isEqualTo(0);
                assertThat(inventory.getAvailableQuantity()).isEqualTo(5);
            }
        });
    }

    @Test
    void shouldProcessOrderModifiedIncreaseQuantity() {
        UUID orderId = newOrderId();
        createInventory(productId1, 8, 2);
        createInventory(productId2, 7, 3);

        MessageEvent orderEvent = createOrderEvent(orderId, productId1, 2, productId2, 3);
        sendOrderModifiedEvent(orderEvent);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<OutboxEvent> events = outboxEventRepository.findAllByOrderId(orderId);
            assertThat(events).hasSize(1);
            assertThat(events.getFirst().getEventType()).isEqualTo(EventType.INVENTORY_RESERVED);
            assertThat(events.getFirst().getStatus()).isEqualTo(PublishmentStatus.PUBLISHED);
        });

        List<Inventory> inventories1 = inventoryRepository.findAllByProductIdIn(Set.of(productId1, productId2));
        assertThat(inventories1).hasSize(2);
        inventories1.forEach(inventory -> {
            if (inventory.getProductId().equals(productId1)) {
                assertThat(inventory.getReservedQuantity()).isEqualTo(4);
                assertThat(inventory.getAvailableQuantity()).isEqualTo(6);
            }
            if (inventory.getProductId().equals(productId2)) {
                assertThat(inventory.getReservedQuantity()).isEqualTo(6);
                assertThat(inventory.getAvailableQuantity()).isEqualTo(4);
            }
        });

        Set<EventItem> modifiedItems = new HashSet<>();
        modifiedItems.add(new EventItem(UUID.randomUUID(), productId1, 5));
        modifiedItems.add(new EventItem(UUID.randomUUID(), productId2, 4));

        MessageEvent modifyEvent = createOrderModifiedEvent(orderId, modifiedItems);
        sendOrderModifiedEvent(modifyEvent);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<OutboxEvent> events = outboxEventRepository.findAllByOrderId(orderId);
            assertThat(events).hasSize(2);

            OutboxEvent latestEvent = events.stream()
                    .max(Comparator.comparing(OutboxEvent::getCreatedAt))
                    .orElseThrow();
            assertThat(latestEvent.getEventType()).isEqualTo(EventType.INVENTORY_RESERVED);
            assertThat(latestEvent.getStatus()).isEqualTo(PublishmentStatus.PUBLISHED);
        });

        List<Reservation> reservations = reservationRepository.findAllByOrderId(orderId);
        assertThat(reservations).hasSize(2);
        reservations.forEach(r -> {
            if (r.getProductId().equals(productId1)) {
                assertThat(r.getAmount()).isEqualTo(5);
            }
            if (r.getProductId().equals(productId2)) {
                assertThat(r.getAmount()).isEqualTo(4);
            }
        });

        List<Inventory> inventories = inventoryRepository.findAllByProductIdIn(Set.of(productId1, productId2));
        assertThat(inventories).hasSize(2);
        inventories.forEach(inventory -> {
            if (inventory.getProductId().equals(productId1)) {
                assertThat(inventory.getReservedQuantity()).isEqualTo(7);
                assertThat(inventory.getAvailableQuantity()).isEqualTo(3);
            }
            if (inventory.getProductId().equals(productId2)) {
                assertThat(inventory.getReservedQuantity()).isEqualTo(7);
                assertThat(inventory.getAvailableQuantity()).isEqualTo(3);
            }
        });
    }

    @Test
    void shouldProcessOrderModifiedDecreaseQuantity() {
        UUID orderId = newOrderId();
        createInventory(productId1, 8, 2);
        createInventory(productId2, 7, 3);

        MessageEvent orderEvent = createOrderEvent(orderId, productId1, 2, productId2, 3);
        sendOrderModifiedEvent(orderEvent);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<OutboxEvent> events = outboxEventRepository.findAllByOrderId(orderId);
            assertThat(events).hasSize(1);
            assertThat(events.getFirst().getEventType()).isEqualTo(EventType.INVENTORY_RESERVED);
            assertThat(events.getFirst().getStatus()).isEqualTo(PublishmentStatus.PUBLISHED);
        });

        List<Inventory> inventories1 = inventoryRepository.findAllByProductIdIn(Set.of(productId1, productId2));
        assertThat(inventories1).hasSize(2);
        inventories1.forEach(inventory -> {
            if (inventory.getProductId().equals(productId1)) {
                assertThat(inventory.getReservedQuantity()).isEqualTo(4);
                assertThat(inventory.getAvailableQuantity()).isEqualTo(6);
            }
            if (inventory.getProductId().equals(productId2)) {
                assertThat(inventory.getReservedQuantity()).isEqualTo(6);
                assertThat(inventory.getAvailableQuantity()).isEqualTo(4);
            }
        });

        Set<EventItem> modifiedItems = new HashSet<>();
        modifiedItems.add(new EventItem(UUID.randomUUID(), productId1, 1));
        modifiedItems.add(new EventItem(UUID.randomUUID(), productId2, 2));

        MessageEvent modifyEvent = createOrderModifiedEvent(orderId, modifiedItems);
        sendOrderModifiedEvent(modifyEvent);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<OutboxEvent> events = outboxEventRepository.findAllByOrderId(orderId);
            assertThat(events).hasSize(2);

            OutboxEvent latestEvent = events.stream()
                    .max(Comparator.comparing(OutboxEvent::getCreatedAt))
                    .orElseThrow();
            assertThat(latestEvent.getEventType()).isEqualTo(EventType.INVENTORY_RESERVED);
        });

        List<Reservation> reservations = reservationRepository.findAllByOrderId(orderId);
        assertThat(reservations).hasSize(2);
        reservations.forEach(r -> {
            if (r.getProductId().equals(productId1)) {
                assertThat(r.getAmount()).isEqualTo(1);
            }
            if (r.getProductId().equals(productId2)) {
                assertThat(r.getAmount()).isEqualTo(2);
            }
        });

        List<Inventory> inventories = inventoryRepository.findAllByProductIdIn(Set.of(productId1, productId2));
        assertThat(inventories).hasSize(2);
        inventories.forEach(inventory -> {
            if (inventory.getProductId().equals(productId1)) {
                assertThat(inventory.getReservedQuantity()).isEqualTo(3);
                assertThat(inventory.getAvailableQuantity()).isEqualTo(7);
            }
            if (inventory.getProductId().equals(productId2)) {
                assertThat(inventory.getReservedQuantity()).isEqualTo(5);
                assertThat(inventory.getAvailableQuantity()).isEqualTo(5);
            }
        });
    }

    @Test
    void shouldProcessOrderModifiedAddNewProduct() {
        UUID orderId = newOrderId();
        Product product3 = new Product();
        product3.setSku("prod3-sku");
        product3.setName("tablet");
        product3.setPrice(BigDecimal.valueOf(800).setScale(6, HALF_UP));
        product3.setCharacteristics("{}");
        product3.setImageUrl("http://some.tablet.url");
        product3.setDescription("description");
        UUID productId3 = productRepository.save(product3).getId();

        createInventory(productId1, 10, 0);
        createInventory(productId2, 10, 0);
        createInventory(productId3, 10, 0);

        MessageEvent orderEvent = createOrderEvent(orderId, productId1, 2, productId2, 3);
        sendOrderCreatedEvent(orderEvent);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<OutboxEvent> events = outboxEventRepository.findAllByOrderId(orderId);
            assertThat(events).hasSize(1);
            assertThat(events.getFirst().getEventType()).isEqualTo(EventType.INVENTORY_RESERVED);
            assertThat(events.getFirst().getStatus()).isEqualTo(PublishmentStatus.PUBLISHED);
        });

        List<Inventory> inventoriesAfterCreate = inventoryRepository.findAllByProductIdIn(Set.of(productId1, productId2));
        assertThat(inventoriesAfterCreate).hasSize(2);
        inventoriesAfterCreate.forEach(inventory -> {
            if (inventory.getProductId().equals(productId1)) {
                assertThat(inventory.getReservedQuantity()).isEqualTo(2);
                assertThat(inventory.getAvailableQuantity()).isEqualTo(8);
            }
            if (inventory.getProductId().equals(productId2)) {
                assertThat(inventory.getReservedQuantity()).isEqualTo(3);
                assertThat(inventory.getAvailableQuantity()).isEqualTo(7);
            }
        });

        Set<EventItem> modifiedItems = new HashSet<>();
        modifiedItems.add(new EventItem(UUID.randomUUID(), productId1, 2));
        modifiedItems.add(new EventItem(UUID.randomUUID(), productId2, 4));
        modifiedItems.add(new EventItem(UUID.randomUUID(), productId3, 5));

        MessageEvent modifyEvent = createOrderModifiedEvent(orderId, modifiedItems);
        sendOrderModifiedEvent(modifyEvent);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<OutboxEvent> events = outboxEventRepository.findAllByOrderId(orderId);
            assertThat(events).hasSize(2);

            OutboxEvent latestEvent = events.stream()
                    .max(Comparator.comparing(OutboxEvent::getCreatedAt))
                    .orElseThrow();
            assertThat(latestEvent.getEventType()).isEqualTo(EventType.INVENTORY_RESERVED);
        });

        List<Reservation> reservations = reservationRepository.findAllByOrderId(orderId);
        assertThat(reservations).hasSize(3);
        reservations.forEach(r -> {
            if (r.getProductId().equals(productId1)) {
                assertThat(r.getAmount()).isEqualTo(2);
            }
            if (r.getProductId().equals(productId2)) {
                assertThat(r.getAmount()).isEqualTo(4);
            }
            if (r.getProductId().equals(productId3)) {
                assertThat(r.getAmount()).isEqualTo(5);
            }
        });

        List<Inventory> inventories = inventoryRepository.findAllByProductIdIn(Set.of(productId1, productId2, productId3));
        assertThat(inventories).hasSize(3);
        inventories.forEach(inventory -> {
            if (inventory.getProductId().equals(productId1)) {
                assertThat(inventory.getReservedQuantity()).isEqualTo(2);
                assertThat(inventory.getAvailableQuantity()).isEqualTo(8);
            }
            if (inventory.getProductId().equals(productId2)) {
                assertThat(inventory.getReservedQuantity()).isEqualTo(4);
                assertThat(inventory.getAvailableQuantity()).isEqualTo(6);
            }
            if (inventory.getProductId().equals(productId3)) {
                assertThat(inventory.getReservedQuantity()).isEqualTo(5);
                assertThat(inventory.getAvailableQuantity()).isEqualTo(5);
            }
        });
    }

    @Test
    void shouldProcessOrderModifiedRemoveProduct() {
        UUID orderId = newOrderId();
        createInventory(productId1, 10, 0);
        createInventory(productId2, 10, 0);

        MessageEvent orderEvent = createOrderEvent(orderId, productId1, 2, productId2, 3);
        sendOrderModifiedEvent(orderEvent);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<OutboxEvent> events = outboxEventRepository.findAllByOrderId(orderId);
            assertThat(events).hasSize(1);
            assertThat(events.getFirst().getEventType()).isEqualTo(EventType.INVENTORY_RESERVED);
            assertThat(events.getFirst().getStatus()).isEqualTo(PublishmentStatus.PUBLISHED);
        });

        List<Inventory> inventoriesAfterCreate = inventoryRepository.findAllByProductIdIn(Set.of(productId1, productId2));
        assertThat(inventoriesAfterCreate).hasSize(2);
        inventoriesAfterCreate.forEach(inventory -> {
            if (inventory.getProductId().equals(productId1)) {
                assertThat(inventory.getReservedQuantity()).isEqualTo(2);
                assertThat(inventory.getAvailableQuantity()).isEqualTo(8);
            }
            if (inventory.getProductId().equals(productId2)) {
                assertThat(inventory.getReservedQuantity()).isEqualTo(3);
                assertThat(inventory.getAvailableQuantity()).isEqualTo(7);
            }
        });

        Set<EventItem> modifiedItems = new HashSet<>();
        modifiedItems.add(new EventItem(UUID.randomUUID(), productId1, 2));

        MessageEvent modifyEvent = createOrderModifiedEvent(orderId, modifiedItems);
        sendOrderModifiedEvent(modifyEvent);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<OutboxEvent> events = outboxEventRepository.findAllByOrderId(orderId);
            assertThat(events).hasSize(2);

            OutboxEvent latestEvent = events.stream()
                    .max(Comparator.comparing(OutboxEvent::getCreatedAt))
                    .orElseThrow();
            assertThat(latestEvent.getEventType()).isEqualTo(EventType.INVENTORY_RESERVED);
        });

        List<Reservation> reservations = reservationRepository.findAllByOrderId(orderId);
        assertThat(reservations).hasSize(2);
        assertThat(reservations).extracting(Reservation::getProductId).containsOnly(productId1, productId2);

        List<Reservation> releasedReservations = reservations.stream()
                .filter(r -> r.getProductId().equals(productId2))
                .toList();
        assertThat(releasedReservations).hasSize(1);
        assertThat(releasedReservations.getFirst().getStatus()).isEqualTo(ReservationStatus.RELEASED);

        List<Inventory> inventories = inventoryRepository.findAllByProductIdIn(Set.of(productId1, productId2));
        assertThat(inventories).hasSize(2);
        inventories.forEach(inventory -> {
            if (inventory.getProductId().equals(productId1)) {
                assertThat(inventory.getReservedQuantity()).isEqualTo(2);
                assertThat(inventory.getAvailableQuantity()).isEqualTo(8);
            }
            if (inventory.getProductId().equals(productId2)) {
                assertThat(inventory.getReservedQuantity()).isEqualTo(0);
                assertThat(inventory.getAvailableQuantity()).isEqualTo(10);
            }
        });
    }

    @Test
    void shouldCreateFailedOutboxEventWhenOrderModifiedInsufficientInventory() {
        UUID orderId = newOrderId();
        createInventory(productId1, 2, 2);
        createInventory(productId2, 5, 0);

        MessageEvent orderEvent = createOrderEvent(orderId, productId1, 2, productId2, 3);
        sendOrderModifiedEvent(orderEvent);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<OutboxEvent> events = outboxEventRepository.findAllByOrderId(orderId);
            assertThat(events).hasSize(1);
            assertThat(events.getFirst().getEventType()).isEqualTo(EventType.INVENTORY_RESERVED);
            assertThat(events.getFirst().getStatus()).isEqualTo(PublishmentStatus.PUBLISHED);
        });

        List<Inventory> inventoriesAfterCreate = inventoryRepository.findAllByProductIdIn(Set.of(productId1, productId2));
        assertThat(inventoriesAfterCreate).hasSize(2);
        inventoriesAfterCreate.forEach(inventory -> {
            if (inventory.getProductId().equals(productId1)) {
                assertThat(inventory.getReservedQuantity()).isEqualTo(4);
                assertThat(inventory.getAvailableQuantity()).isEqualTo(0);
            }
            if (inventory.getProductId().equals(productId2)) {
                assertThat(inventory.getReservedQuantity()).isEqualTo(3);
                assertThat(inventory.getAvailableQuantity()).isEqualTo(2);
            }
        });

        Set<EventItem> modifiedItems = new HashSet<>();
        modifiedItems.add(new EventItem(UUID.randomUUID(), productId1, 5));

        MessageEvent modifyEvent = createOrderModifiedEvent(orderId, modifiedItems);
        sendOrderModifiedEvent(modifyEvent);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<OutboxEvent> events = outboxEventRepository.findAllByOrderId(orderId);
            assertThat(events).hasSize(2);

            OutboxEvent latestEvent = events.stream()
                    .max(Comparator.comparing(OutboxEvent::getCreatedAt))
                    .orElseThrow();
            assertThat(latestEvent.getEventType()).isEqualTo(EventType.INVENTORY_FAILED);
            assertThat(latestEvent.getPayload()).contains("Not enough requestedItems in inventory!");
        });
    }

    @Test
    void shouldProcessOrderModifiedComplexChanges() {
        UUID orderId = newOrderId();

        Product product3 = new Product();
        product3.setSku("prod3-sku");
        product3.setName("tablet");
        product3.setPrice(BigDecimal.valueOf(800).setScale(6, HALF_UP));
        product3.setCharacteristics("{}");
        product3.setImageUrl("http://some.tablet.url");
        product3.setDescription("description");
        UUID productId3 = productRepository.save(product3).getId();

        Product product4 = new Product();
        product4.setSku("prod4-sku");
        product4.setName("headphones");
        product4.setPrice(BigDecimal.valueOf(200).setScale(6, HALF_UP));
        product4.setCharacteristics("{}");
        product4.setImageUrl("http://some.headphones.url");
        product4.setDescription("description");
        UUID productId4 = productRepository.save(product4).getId();

        createInventory(productId1, 20, 0);
        createInventory(productId2, 20, 0);
        createInventory(productId3, 20, 0);
        createInventory(productId4, 20, 0);

        MessageEvent orderEvent = createOrderEvent(orderId, productId1, 5, productId2, 3, productId3, 4);
        sendOrderCreatedEvent(orderEvent);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<OutboxEvent> events = outboxEventRepository.findAllByOrderId(orderId);
            assertThat(events).hasSize(1);
            assertThat(events.getFirst().getEventType()).isEqualTo(EventType.INVENTORY_RESERVED);
            assertThat(events.getFirst().getStatus()).isEqualTo(PublishmentStatus.PUBLISHED);
        });

        List<Inventory> inventoriesAfterCreate = inventoryRepository.findAllByProductIdIn(Set.of(productId1, productId2, productId3));
        assertThat(inventoriesAfterCreate).hasSize(3);
        inventoriesAfterCreate.forEach(inventory -> {
            if (inventory.getProductId().equals(productId1)) {
                assertThat(inventory.getReservedQuantity()).isEqualTo(5);
                assertThat(inventory.getAvailableQuantity()).isEqualTo(15);
            }
            if (inventory.getProductId().equals(productId2)) {
                assertThat(inventory.getReservedQuantity()).isEqualTo(3);
                assertThat(inventory.getAvailableQuantity()).isEqualTo(17);
            }
            if (inventory.getProductId().equals(productId3)) {
                assertThat(inventory.getReservedQuantity()).isEqualTo(4);
                assertThat(inventory.getAvailableQuantity()).isEqualTo(16);
            }
        });

        List<Reservation> reservationsAfterCreate = reservationRepository.findAllByOrderId(orderId);
        assertThat(reservationsAfterCreate).hasSize(3);
        reservationsAfterCreate.forEach(r -> {
            if (r.getProductId().equals(productId1)) assertThat(r.getAmount()).isEqualTo(5);
            if (r.getProductId().equals(productId2)) assertThat(r.getAmount()).isEqualTo(3);
            if (r.getProductId().equals(productId3)) assertThat(r.getAmount()).isEqualTo(4);
        });

        Set<EventItem> modifiedItems = new HashSet<>();
        modifiedItems.add(new EventItem(UUID.randomUUID(), productId1, 8));
        modifiedItems.add(new EventItem(UUID.randomUUID(), productId2, 2));
        modifiedItems.add(new EventItem(UUID.randomUUID(), productId4, 6));

        MessageEvent modifyEvent = createOrderModifiedEvent(orderId, modifiedItems);
        sendOrderModifiedEvent(modifyEvent);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<OutboxEvent> events = outboxEventRepository.findAllByOrderId(orderId);
            assertThat(events).hasSize(2);

            OutboxEvent latestEvent = events.stream()
                    .max(Comparator.comparing(OutboxEvent::getCreatedAt))
                    .orElseThrow();
            assertThat(latestEvent.getEventType()).isEqualTo(EventType.INVENTORY_RESERVED);
            assertThat(latestEvent.getStatus()).isEqualTo(PublishmentStatus.PUBLISHED);
        });

        List<Reservation> reservations = reservationRepository.findAllByOrderId(orderId);
        assertThat(reservations).hasSize(4);
        List<Reservation> reservedReservations = reservations.stream()
                .filter(r -> r.getStatus() == ReservationStatus.RESERVED)
                .toList();
        assertThat(reservedReservations).hasSize(3);
        reservedReservations.forEach(r -> {
            if (r.getProductId().equals(productId1)) assertThat(r.getAmount()).isEqualTo(8);
            if (r.getProductId().equals(productId2)) assertThat(r.getAmount()).isEqualTo(2);
            if (r.getProductId().equals(productId4)) assertThat(r.getAmount()).isEqualTo(6);
        });

        List<Reservation> releasedReservations = reservations.stream()
                .filter(r -> r.getStatus() == ReservationStatus.RELEASED)
                .toList();
        assertThat(releasedReservations).hasSize(1);
        assertThat(releasedReservations.getFirst().getProductId()).isEqualTo(productId3);

        List<Inventory> inventories = inventoryRepository.findAllByProductIdIn(Set.of(productId1, productId2, productId3, productId4));
        assertThat(inventories).hasSize(4);
        inventories.forEach(inventory -> {
            if (inventory.getProductId().equals(productId1)) {
                assertThat(inventory.getReservedQuantity()).isEqualTo(8);
                assertThat(inventory.getAvailableQuantity()).isEqualTo(12);
            }
            if (inventory.getProductId().equals(productId2)) {
                assertThat(inventory.getReservedQuantity()).isEqualTo(2);
                assertThat(inventory.getAvailableQuantity()).isEqualTo(18);
            }
            if (inventory.getProductId().equals(productId3)) {
                assertThat(inventory.getReservedQuantity()).isEqualTo(0);
                assertThat(inventory.getAvailableQuantity()).isEqualTo(20);
            }
            if (inventory.getProductId().equals(productId4)) {
                assertThat(inventory.getReservedQuantity()).isEqualTo(6);
                assertThat(inventory.getAvailableQuantity()).isEqualTo(14);
            }
        });
    }
}