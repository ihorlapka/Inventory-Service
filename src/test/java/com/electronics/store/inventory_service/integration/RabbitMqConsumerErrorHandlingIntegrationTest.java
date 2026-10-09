package com.electronics.store.inventory_service.integration;

import com.electronics.store.inventory_service.messaging.RabbitMqProperties;
import com.electronics.store.inventory_service.messaging.message.*;
import com.electronics.store.inventory_service.persistence.model.Inventory;
import com.electronics.store.inventory_service.persistence.model.OutboxEvent;
import com.electronics.store.inventory_service.persistence.model.Product;
import com.electronics.store.inventory_service.persistence.model.enums.EventType;
import com.electronics.store.inventory_service.persistence.model.enums.PublishmentStatus;
import com.electronics.store.inventory_service.persistence.repositories.InventoryRepository;
import com.electronics.store.inventory_service.persistence.repositories.OutboxEventRepository;
import com.electronics.store.inventory_service.persistence.repositories.ProductRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.electronics.store.inventory_service.persistence.model.enums.Currency.USD;
import static com.electronics.store.inventory_service.persistence.model.enums.OrderStatus.PENDING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@ActiveProfiles("test")
@SpringBootTest
@Testcontainers
@TestPropertySource("classpath:application-test.yaml")
class RabbitMqConsumerErrorHandlingIntegrationTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:17.5"))
            .withDatabaseName("inventory_test")
            .withUsername("test")
            .withPassword("test")
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
    private ProductRepository productRepository;

    @Autowired
    private RabbitMqProperties rabbitProps;

    private UUID productId1;
    private UUID productId2;

    @BeforeEach
    void setUp() {
        Product product1 = new Product(productId1, "prod1-sku", "laptop", BigDecimal.valueOf(1200).setScale(6, RoundingMode.HALF_UP), "{}", "http://some.laptop.url", "description");
        Product product2 = new Product(productId2, "prod2-sku", "phone", BigDecimal.valueOf(550).setScale(6, RoundingMode.HALF_UP), "{}", "http://some.phone.url", "description");
        productId1 = productRepository.save(product1).getId();
        productId2 = productRepository.save(product2).getId();
    }

    @AfterEach
    void tearDown() {
        outboxEventRepository.deleteAll();
        inventoryRepository.deleteAll();
        productRepository.deleteAll();
    }

    private UUID newOrderId() {
        return UUID.randomUUID();
    }

    private void createInventory(UUID productId, int availableQty, int reservedQty) {
        Inventory inventory = new Inventory();
        inventory.setProductId(productId);
        inventory.setAvailableQuantity(availableQty);
        inventory.setReservedQuantity(reservedQty);
        inventoryRepository.save(inventory);
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
        sendOrderEvent(event, "order.created");
    }

    private void sendOrderCancelledEvent(MessageEvent event) {
        sendOrderEvent(event, "order.cancelled");
    }

    private void sendOrderModifiedEvent(MessageEvent event) {
        sendOrderEvent(event, "order.modified");
    }

    private MessageEvent createOrderCreatedEvent(UUID orderId, Set<EventItem> items) {
        OrderCreatedData orderData = new OrderCreatedData(UUID.randomUUID(), USD, items);
        return new MessageEvent(
                UUID.randomUUID(),
                EventType.ORDER_CREATED,
                orderId,
                PENDING,
                OffsetDateTime.now(),
                orderData
        );
    }

    private MessageEvent createOrderCancelledEvent(UUID orderId) {
        OrderCancelledData orderData = new OrderCancelledData("Customer requested cancellation");
        return new MessageEvent(
                UUID.randomUUID(),
                EventType.ORDER_CANCELLED,
                orderId,
                PENDING,
                OffsetDateTime.now(),
                orderData
        );
    }

    private MessageEvent createOrderModifiedEvent(UUID orderId, Set<EventItem> items) {
        OrderModifiedData orderData = new OrderModifiedData(items);
        return new MessageEvent(
                UUID.randomUUID(),
                EventType.ORDER_MODIFIED,
                orderId,
                PENDING,
                OffsetDateTime.now(),
                orderData
        );
    }

    @Test
    void shouldRejectAndNotRequeueWhenOrderCreatedHasInvalidEventData() {
        UUID orderId = newOrderId();
        createInventory(productId1, 10, 0);
        createInventory(productId2, 5, 0);

        MessageEvent event = new MessageEvent(
                UUID.randomUUID(),
                EventType.ORDER_CREATED,
                orderId,
                PENDING,
                OffsetDateTime.now(),
                new OrderCancelledData("Invalid data type for ORDER_CREATED")
        );

        sendOrderCreatedEvent(event);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<OutboxEvent> events = outboxEventRepository.findAllByOrderId(orderId);
            assertThat(events).isEmpty();
        });

        List<Inventory> inventories = inventoryRepository.findAllByProductIdIn(Set.of(productId1, productId2));
        assertThat(inventories).hasSize(2);
        inventories.forEach(inventory -> {
            assertThat(inventory.getReservedQuantity()).isEqualTo(0);
            assertThat(inventory.getAvailableQuantity()).isEqualTo(inventory.getProductId().equals(productId1) ? 10 : 5);
        });
    }

    @Test
    void shouldRejectAndNotRequeueWhenOrderCancelledHasInvalidEventData() {
        UUID orderId = newOrderId();
        createInventory(productId1, 10, 0);

        MessageEvent event = new MessageEvent(
                UUID.randomUUID(),
                EventType.ORDER_CANCELLED,
                orderId,
                PENDING,
                OffsetDateTime.now(),
                new OrderCreatedData(UUID.randomUUID(), USD, Set.of())
        );

        sendOrderCancelledEvent(event);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<OutboxEvent> events = outboxEventRepository.findAllByOrderId(orderId);
            assertThat(events).isEmpty();
        });
    }

    @Test
    void shouldRejectAndNotRequeueWhenOrderModifiedHasInvalidEventData() {
        UUID orderId = newOrderId();
        createInventory(productId1, 10, 0);

        MessageEvent event = new MessageEvent(
                UUID.randomUUID(),
                EventType.ORDER_MODIFIED,
                orderId,
                PENDING,
                OffsetDateTime.now(),
                new OrderCreatedData(UUID.randomUUID(), USD, Set.of())
        );

        sendOrderModifiedEvent(event);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<OutboxEvent> events = outboxEventRepository.findAllByOrderId(orderId);
            assertThat(events).isEmpty();
        });
    }

    @Test
    void shouldRejectAndNotRequeueWhenOrderCreatedMissingInventoryRecords() {
        UUID orderId = newOrderId();
        createInventory(productId1, 10, 0);

        UUID missingProductId = UUID.randomUUID();
        Set<EventItem> items = Set.of(
                new EventItem(UUID.randomUUID(), productId1, 2),
                new EventItem(UUID.randomUUID(), missingProductId, 3)
        );
        MessageEvent event = createOrderCreatedEvent(orderId, items);

        sendOrderCreatedEvent(event);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<OutboxEvent> events = outboxEventRepository.findAllByOrderId(orderId);
            assertThat(events).hasSize(1);
            OutboxEvent outboxEvent = events.getFirst();
            assertThat(outboxEvent.getEventType()).isEqualTo(EventType.INVENTORY_FAILED);
            assertThat(outboxEvent.getStatus()).isIn(PublishmentStatus.NEW, PublishmentStatus.PUBLISHED);
            assertThat(outboxEvent.getPayload()).contains("No records in db for requested items!");
        });
    }

    @Test
    void shouldRejectAndNotRequeueWhenOrderCreatedInsufficientInventory() {
        UUID orderId = newOrderId();
        createInventory(productId1, 2, 0);
        createInventory(productId2, 5, 0);

        Set<EventItem> items = Set.of(
                new EventItem(UUID.randomUUID(), productId1, 5),
                new EventItem(UUID.randomUUID(), productId2, 3)
        );
        MessageEvent event = createOrderCreatedEvent(orderId, items);

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
        inventories.forEach(inventory -> {
            assertThat(inventory.getReservedQuantity()).isEqualTo(0);
        });
    }

    @Test
    void shouldRejectAndNotRequeueWhenMalformedJsonMessageSent() {
        UUID orderId = newOrderId();
        createInventory(productId1, 10, 0);

        String malformedJson = "{ invalid json }";
        MessageProperties messageProperties = new MessageProperties();
        messageProperties.setMessageId(UUID.randomUUID().toString());
        messageProperties.setCorrelationId(UUID.randomUUID().toString());
        messageProperties.setTimestamp(new java.util.Date());
        messageProperties.setContentType("application/json");

        Message message = new Message(malformedJson.getBytes(), messageProperties);

        rabbitTemplate.send(rabbitProps.getOrdersExchange(), "order.created", message);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<OutboxEvent> events = outboxEventRepository.findAllByOrderId(orderId);
            assertThat(events).isEmpty();
        });
    }

    @Test
    void shouldRejectAndNotRequeueWhenMessageMissingRequiredFields() {
        UUID orderId = newOrderId();
        createInventory(productId1, 10, 0);

        String incompleteJson = """
            {
                "eventId": "%s",
                "eventType": "ORDER_CREATED",
                "orderId": "%s"
            }
            """.formatted(UUID.randomUUID(), orderId);

        MessageProperties messageProperties = new MessageProperties();
        messageProperties.setMessageId(UUID.randomUUID().toString());
        messageProperties.setCorrelationId(UUID.randomUUID().toString());
        messageProperties.setTimestamp(new java.util.Date());
        messageProperties.setContentType("application/json");

        Message message = new Message(incompleteJson.getBytes(), messageProperties);

        rabbitTemplate.send(rabbitProps.getOrdersExchange(), "order.created", message);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<OutboxEvent> events = outboxEventRepository.findAllByOrderId(orderId);
            assertThat(events).isEmpty();
        });
    }

    @Test
    void shouldRejectAndNotRequeueWhenOrderCancelledNoReservationsExist() {
        UUID orderId = newOrderId();
        createInventory(productId1, 10, 0);

        MessageEvent event = createOrderCancelledEvent(orderId);

        sendOrderCancelledEvent(event);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<OutboxEvent> events = outboxEventRepository.findAllByOrderId(orderId);
            assertThat(events).isEmpty();
        });
    }

    @Test
    void shouldRejectAndNotRequeueWhenOrderModifiedInsufficientInventory() {
        UUID orderId = newOrderId();
        createInventory(productId1, 2, 2);
        createInventory(productId2, 5, 0);

        MessageEvent orderEvent = createOrderCreatedEvent(orderId, Set.of(
                new EventItem(UUID.randomUUID(), productId1, 2),
                new EventItem(UUID.randomUUID(), productId2, 3)
        ));
        sendOrderCreatedEvent(orderEvent);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<OutboxEvent> events = outboxEventRepository.findAllByOrderId(orderId);
            assertThat(events).hasSize(1);
            assertThat(events.getFirst().getEventType()).isEqualTo(EventType.INVENTORY_RESERVED);
        });

        Set<EventItem> modifiedItems = Set.of(
                new EventItem(UUID.randomUUID(), productId1, 5)
        );
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
    void shouldProcessValidOrderCreatedSuccessfully() {
        UUID orderId = newOrderId();
        createInventory(productId1, 10, 0);
        createInventory(productId2, 5, 0);

        Set<EventItem> items = Set.of(
                new EventItem(UUID.randomUUID(), productId1, 2),
                new EventItem(UUID.randomUUID(), productId2, 3)
        );
        MessageEvent event = createOrderCreatedEvent(orderId, items);

        sendOrderCreatedEvent(event);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<OutboxEvent> events = outboxEventRepository.findAllByOrderId(orderId);
            assertThat(events).hasSize(1);
            OutboxEvent outboxEvent = events.getFirst();
            assertThat(outboxEvent.getEventType()).isEqualTo(EventType.INVENTORY_RESERVED);
            assertThat(outboxEvent.getStatus()).isIn(PublishmentStatus.NEW, PublishmentStatus.PUBLISHED);
        });

        List<Inventory> inventories = inventoryRepository.findAllByProductIdIn(Set.of(productId1, productId2));
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
    }
}