package com.electronics.store.inventory_service.integration;

import com.electronics.store.inventory_service.messaging.message.*;
import com.electronics.store.inventory_service.persistence.model.Inventory;
import com.electronics.store.inventory_service.persistence.model.OutboxEvent;
import com.electronics.store.inventory_service.persistence.model.Reservation;
import com.electronics.store.inventory_service.persistence.model.enums.OrderEventType;
import com.electronics.store.inventory_service.persistence.model.enums.PublishmentStatus;
import com.electronics.store.inventory_service.persistence.model.enums.ReservationStatus;
import com.electronics.store.inventory_service.persistence.repositories.InventoryRepository;
import com.electronics.store.inventory_service.persistence.repositories.OutboxEventRepository;
import com.electronics.store.inventory_service.persistence.repositories.ReservationRepository;
import com.electronics.store.outbox_event_publisher.OutboxEventHandler;
import com.electronics.store.outbox_event_publisher.OutboxEventManager;
import com.electronics.store.outbox_event_publisher.OutboxProcessor;
import com.electronics.store.outbox_event_publisher.rabbit.RabbitMqPublisher;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@ActiveProfiles("test")
@SpringBootTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource("classpath:application-test.yaml")
class ReservationProcessorIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.5"))
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
    private OutboxEventHandler<OutboxEvent> outboxEventHandler;

    @Autowired
    private OutboxEventManager<OutboxEvent> outboxEventManager;

    @Autowired
    private OutboxProcessor<OutboxEvent> outboxProcessor;

    @Autowired
    private RabbitMqPublisher rabbitMqPublisher;

    private UUID productId1;
    private UUID productId2;

    @BeforeEach
    void setUp() {
        productId1 = UUID.randomUUID();
        productId2 = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        reservationRepository.deleteAll();
        outboxEventRepository.deleteAll();
        inventoryRepository.deleteAll();
    }

    private UUID newOrderId() {
        return UUID.randomUUID();
    }

    @Test
    void shouldProcessOrderCreatedAndPublishOutboxEventViaOutboxEventPublisher() {
        UUID orderId = newOrderId();
        createInventory(productId1, 10, 0);
        createInventory(productId2, 5, 0);

        MessageEventIn event = createOrderEvent(orderId, productId1, 2, productId2, 3);

        sendOrderEvent(event);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<OutboxEvent> events = outboxEventRepository.findAllByOrderId(orderId);
            assertThat(events).hasSize(1);

            OutboxEvent outboxEvent = events.getFirst();
            assertThat(outboxEvent.getEventType()).isEqualTo(OrderEventType.INVENTORY_RESERVED);
            assertThat(outboxEvent.getStatus()).isEqualTo(PublishmentStatus.PUBLISHED);
            assertThat(outboxEvent.getOrderId()).isEqualTo(orderId);
            assertThat(outboxEvent.getPayload()).isNotNull();
            assertThat(outboxEvent.getPayload()).contains("INVENTORY_RESERVED");
            assertThat(outboxEvent.getPayload()).contains("reservedItems");
        });

        List<Reservation> reservations = reservationRepository.findAllByOrderId(orderId);
        assertThat(reservations).hasSize(2);
        assertThat(reservations).allMatch(r -> r.getStatus() == ReservationStatus.RESERVED);

        assertThat(outboxEventHandler).isNotNull();
        assertThat(outboxEventManager).isNotNull();
        assertThat(outboxProcessor).isNotNull();
        assertThat(rabbitMqPublisher).isNotNull();
    }

    @Test
    void shouldCreateFailedOutboxEventWhenInventoryMissing() {
        UUID orderId = newOrderId();
        createInventory(productId1, 10, 0);

        UUID missingProductId = UUID.randomUUID();
        MessageEventIn event = createOrderEvent(orderId, productId1, 2, missingProductId, 3);

        sendOrderEvent(event);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<OutboxEvent> events = outboxEventRepository.findAllByOrderId(orderId);
            assertThat(events).hasSize(1);

            OutboxEvent outboxEvent = events.getFirst();
            assertThat(outboxEvent.getEventType()).isEqualTo(OrderEventType.INVENTORY_FAILED);
            assertThat(outboxEvent.getStatus()).isEqualTo(PublishmentStatus.PUBLISHED);
            assertThat(outboxEvent.getPayload()).contains("No records in db for requested items!");
        });

        assertThat(reservationRepository.findAllByOrderId(orderId)).isEmpty();
    }

    @Test
    void shouldCreateFailedOutboxEventWhenInsufficientInventory() {
        UUID orderId = newOrderId();
        createInventory(productId1, 2, 0);
        createInventory(productId2, 5, 0);

        MessageEventIn event = createOrderEvent(orderId, productId1, 5, productId2, 3);

        sendOrderEvent(event);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<OutboxEvent> events = outboxEventRepository.findAllByOrderId(orderId);
            assertThat(events).hasSize(1);

            OutboxEvent outboxEvent = events.getFirst();
            assertThat(outboxEvent.getEventType()).isEqualTo(OrderEventType.INVENTORY_FAILED);
            assertThat(outboxEvent.getStatus()).isIn(PublishmentStatus.NEW, PublishmentStatus.PUBLISHED);
            assertThat(outboxEvent.getPayload()).contains("Not enough items in inventory!");
        });

        assertThat(reservationRepository.findAllByOrderId(orderId)).isEmpty();
    }

    private void sendOrderEvent(MessageEventIn event) {
        MessageProperties messageProperties = new MessageProperties();
        messageProperties.setMessageId(UUID.randomUUID().toString());
        messageProperties.setCorrelationId(UUID.randomUUID().toString());
        messageProperties.setTimestamp(new java.util.Date());
        messageProperties.setContentType("application/json");

        Message message = new Message(
                rabbitTemplate.getMessageConverter().toMessage(event, messageProperties).getBody(),
                messageProperties
        );

        rabbitTemplate.send("orders.exchange", "orders.created", message);
    }

    private void createInventory(UUID productId, int availableQty, int reservedQty) {
        Inventory inventory = new Inventory();
        inventory.setProductId(productId);
        inventory.setAvailableQuantity(availableQty);
        inventory.setReservedQuantity(reservedQty);
        inventoryRepository.save(inventory);
    }

    private MessageEventIn createOrderEvent(UUID orderId, UUID productId1, int qty1, UUID productId2, int qty2) {
        Set<EventItem> items = new HashSet<>();
        items.add(new EventItem(UUID.randomUUID(), productId1, "Product 1", qty1, new BigDecimal("50.00"), "http://example.com/p1"));
        items.add(new EventItem(UUID.randomUUID(), productId2, "Product 2", qty2, new BigDecimal("75.00"), "http://example.com/p2"));

        OrderCreatedData orderData = new OrderCreatedData(UUID.randomUUID(), com.electronics.store.inventory_service.persistence.model.enums.Currency.USD,
                new BigDecimal("275.00"), items);

        return new MessageEventIn(
                UUID.randomUUID(),
                OrderEventType.ORDER_CREATED,
                orderId,
                com.electronics.store.inventory_service.persistence.model.enums.OrderStatus.PENDING,
                OffsetDateTime.now(),
                orderData
        );
    }
}