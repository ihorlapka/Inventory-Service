package com.electronics.store.inventory_service.processor;

import com.electronics.store.inventory_service.messaging.message.*;
import com.electronics.store.inventory_service.persistence.model.Inventory;
import com.electronics.store.inventory_service.persistence.model.OutboxEvent;
import com.electronics.store.inventory_service.persistence.model.Product;
import com.electronics.store.inventory_service.persistence.model.Reservation;
import com.electronics.store.inventory_service.persistence.model.enums.Currency;
import com.electronics.store.inventory_service.persistence.model.enums.EventType;
import com.electronics.store.inventory_service.persistence.model.enums.OrderStatus;
import com.electronics.store.inventory_service.persistence.model.enums.ReservationStatus;
import com.electronics.store.inventory_service.persistence.model.enums.PublishmentStatus;
import com.electronics.store.inventory_service.persistence.services.InventoryService;
import com.electronics.store.inventory_service.persistence.services.OutboxEventService;
import com.electronics.store.inventory_service.persistence.services.ProductService;
import com.electronics.store.inventory_service.persistence.services.ReservationService;
import com.electronics.store.inventory_service.messaging.message.OrderCancelledData;
import com.electronics.store.inventory_service.messaging.message.OrderModifiedData;
import com.electronics.store.outbox_event_publisher.PublishmentTriggerEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReservationProcessorTest {

    @Mock
    private InventoryService inventoryService;

    @Mock
    private ReservationService reservationService;

    @Mock
    private OutboxEventService outboxEventService;

    @Mock
    private ProductService productService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private ReservationModifier reservationModifier;

    @InjectMocks
    private ReservationProcessor reservationProcessor;

    @Captor
    private ArgumentCaptor<List<Reservation>> reservationCaptor;

    @Captor
    private ArgumentCaptor<OutboxEvent> outboxEventCaptor;

    @Captor
    private ArgumentCaptor<PublishmentTriggerEvent> triggerEventCaptor;

    private UUID orderId;
    private UUID productId1;
    private UUID productId2;
    private OffsetDateTime now;

    @BeforeEach
    void setUp() {
        orderId = UUID.randomUUID();
        productId1 = UUID.randomUUID();
        productId2 = UUID.randomUUID();
        now = OffsetDateTime.now();
    }

    private MessageEvent createOrderEvent(UUID eventId, Set<EventItem> items) {
        OrderCreatedData orderData = new OrderCreatedData(
                UUID.randomUUID(),
                Currency.USD,
                new BigDecimal("100.00"),
                items
        );
        return new MessageEvent(
                eventId,
                EventType.ORDER_CREATED,
                orderId,
                OrderStatus.PENDING,
                now,
                orderData
        );
    }

    private EventItem createEventItem(UUID id, UUID productId, int quantity) {
        return new EventItem(
                id,
                productId,
                quantity
        );
    }

    private Inventory createInventory(UUID productId, int availableQty, int reservedQty) {
        Inventory inventory = new Inventory();
        inventory.setProductId(productId);
        inventory.setAvailableQuantity(availableQty);
        inventory.setReservedQuantity(reservedQty);
        return inventory;
    }

    private Product createProduct(UUID productId) {
        Product product = new Product();
        product.setId(productId);
        product.setSku("SKU-" + productId);
        product.setName("Product " + productId);
        product.setPrice(new BigDecimal("99.99"));
        product.setCharacteristics("{}");
        product.setImageUrl("http://example.com/image.jpg");
        product.setDescription("Test product");
        return product;
    }

    @Test
    void processOrderCreated_successfulReservation() {
        UUID eventId = UUID.randomUUID();
        EventItem item1 = createEventItem(UUID.randomUUID(), productId1, 2);
        EventItem item2 = createEventItem(UUID.randomUUID(), productId2, 3);
        Set<EventItem> items = Set.of(item1, item2);
        MessageEvent event = createOrderEvent(eventId, items);

        Inventory inv1 = createInventory(productId1, 10, 0);
        Inventory inv2 = createInventory(productId2, 10, 0);
        when(inventoryService.findInventoriesByProductIds(anySet())).thenReturn(List.of(inv1, inv2));
        when(productService.findByProductIdIn(Set.of(productId1, productId2)))
                .thenReturn(List.of(createProduct(productId1), createProduct(productId2)));

        reservationProcessor.processOrderCreated(event);

        verify(inventoryService).findInventoriesByProductIds(Set.of(productId1, productId2));
        verify(reservationService).saveAll(reservationCaptor.capture());
        verify(outboxEventService).persist(outboxEventCaptor.capture());
        verify(eventPublisher).publishEvent(triggerEventCaptor.capture());

        List<Reservation> reservations = reservationCaptor.getValue();
        assertEquals(2, reservations.size());
        assertTrue(reservations.stream().allMatch(r -> r.getStatus() == ReservationStatus.RESERVED));
        assertTrue(reservations.stream().allMatch(r -> r.getOrderId().equals(orderId)));
        assertTrue(reservations.stream().anyMatch(r -> r.getAmount() == 2 && r.getProductId().equals(productId1)));
        assertTrue(reservations.stream().anyMatch(r -> r.getAmount() == 3 && r.getProductId().equals(productId2)));

        OutboxEvent outboxEvent = outboxEventCaptor.getValue();
        assertEquals(EventType.INVENTORY_RESERVED, outboxEvent.getEventType());
        assertEquals(orderId, outboxEvent.getOrderId());
        assertEquals(PublishmentStatus.NEW, outboxEvent.getStatus());

        PublishmentTriggerEvent triggerEvent = triggerEventCaptor.getValue();
        assertEquals(orderId, triggerEvent.orderId());

        assertEquals(8, inv1.getAvailableQuantity());
        assertEquals(2, inv1.getReservedQuantity());
        assertEquals(7, inv2.getAvailableQuantity());
        assertEquals(3, inv2.getReservedQuantity());
    }

    @Test
    void processOrderCreated_missingInventoryRecords() {
        UUID eventId = UUID.randomUUID();
        EventItem item1 = createEventItem(UUID.randomUUID(), productId1, 2);
        EventItem item2 = createEventItem(UUID.randomUUID(), productId2, 3);
        Set<EventItem> items = Set.of(item1, item2);
        MessageEvent event = createOrderEvent(eventId, items);

        Inventory inv1 = createInventory(productId1, 10, 0);
        when(inventoryService.findInventoriesByProductIds(anySet()))
                .thenReturn(List.of(inv1));

        reservationProcessor.processOrderCreated(event);

        verify(inventoryService).findInventoriesByProductIds(Set.of(productId1, productId2));
        verify(reservationService, never()).saveAll(any());
        verify(outboxEventService).persist(outboxEventCaptor.capture());
        verify(eventPublisher, never()).publishEvent(any());

        OutboxEvent outboxEvent = outboxEventCaptor.getValue();
        assertEquals(EventType.INVENTORY_FAILED, outboxEvent.getEventType());
        assertEquals(PublishmentStatus.NEW, outboxEvent.getStatus());
    }

    @Test
    void processOrderCreated_insufficientInventory() {
        UUID eventId = UUID.randomUUID();
        EventItem item1 = createEventItem(UUID.randomUUID(), productId1, 15);
        EventItem item2 = createEventItem(UUID.randomUUID(), productId2, 3);
        Set<EventItem> items = Set.of(item1, item2);
        MessageEvent event = createOrderEvent(eventId, items);

        Inventory inv1 = createInventory(productId1, 10, 0);
        Inventory inv2 = createInventory(productId2, 10, 0);
        when(inventoryService.findInventoriesByProductIds(anySet()))
                .thenReturn(List.of(inv1, inv2));

        reservationProcessor.processOrderCreated(event);

        verify(inventoryService).findInventoriesByProductIds(Set.of(productId1, productId2));
        verify(reservationService, never()).saveAll(any());
        verify(outboxEventService).persist(outboxEventCaptor.capture());
        verify(eventPublisher, never()).publishEvent(any());

        OutboxEvent outboxEvent = outboxEventCaptor.getValue();
        assertEquals(EventType.INVENTORY_FAILED, outboxEvent.getEventType());
        assertEquals(PublishmentStatus.NEW, outboxEvent.getStatus());
    }

    @Test
    void processOrderCreated_partialMissingAndInsufficient() {
        UUID eventId = UUID.randomUUID();
        EventItem item1 = createEventItem(UUID.randomUUID(), productId1, 15);
        UUID missingProductId = UUID.randomUUID();
        EventItem item2 = createEventItem(UUID.randomUUID(), missingProductId, 3);
        Set<EventItem> items = Set.of(item1, item2);
        MessageEvent event = createOrderEvent(eventId, items);

        Inventory inv1 = createInventory(productId1, 10, 0);
        when(inventoryService.findInventoriesByProductIds(anySet()))
                .thenReturn(List.of(inv1));

        reservationProcessor.processOrderCreated(event);

        verify(inventoryService).findInventoriesByProductIds(Set.of(productId1, missingProductId));
        verify(reservationService, never()).saveAll(any());
        verify(outboxEventService).persist(outboxEventCaptor.capture());
        verify(eventPublisher, never()).publishEvent(any());

        OutboxEvent outboxEvent = outboxEventCaptor.getValue();
        assertEquals(EventType.INVENTORY_FAILED, outboxEvent.getEventType());
    }

    @Test
    void processOrderCreated_singleItemSuccess() {
        UUID eventId = UUID.randomUUID();
        EventItem item = createEventItem(UUID.randomUUID(), productId1, 5);
        MessageEvent event = createOrderEvent(eventId, Set.of(item));

        Inventory inv = createInventory(productId1, 10, 2);
        when(inventoryService.findInventoriesByProductIds(anySet()))
                .thenReturn(List.of(inv));
        when(productService.findByProductIdIn(Set.of(productId1)))
                .thenReturn(List.of(createProduct(productId1)));

        reservationProcessor.processOrderCreated(event);

        verify(reservationService).saveAll(reservationCaptor.capture());
        verify(outboxEventService).persist(outboxEventCaptor.capture());
        verify(eventPublisher).publishEvent(triggerEventCaptor.capture());

        List<Reservation> reservations = reservationCaptor.getValue();
        assertEquals(1, reservations.size());
        assertEquals(5, reservations.getFirst().getAmount());

        assertEquals(5, inv.getAvailableQuantity());
        assertEquals(7, inv.getReservedQuantity());
    }

    @Test
    void processOrderCreated_exactAvailableQuantity() {
        UUID eventId = UUID.randomUUID();
        EventItem item = createEventItem(UUID.randomUUID(), productId1, 10);
        MessageEvent event = createOrderEvent(eventId, Set.of(item));

        Inventory inv = createInventory(productId1, 10, 0);
        when(inventoryService.findInventoriesByProductIds(anySet()))
                .thenReturn(List.of(inv));
        when(productService.findByProductIdIn(Set.of(productId1)))
                .thenReturn(List.of(createProduct(productId1)));

        reservationProcessor.processOrderCreated(event);

        verify(reservationService).saveAll(reservationCaptor.capture());
        verify(outboxEventService).persist(outboxEventCaptor.capture());

        assertEquals(0, inv.getAvailableQuantity());
        assertEquals(10, inv.getReservedQuantity());
    }

    @Test
    void processOrderCreated_zeroQuantity() {
        UUID eventId = UUID.randomUUID();
        EventItem item = createEventItem(UUID.randomUUID(), productId1, 0);
        MessageEvent event = createOrderEvent(eventId, Set.of(item));

        Inventory inv = createInventory(productId1, 10, 0);
        when(inventoryService.findInventoriesByProductIds(anySet()))
                .thenReturn(List.of(inv));
        when(productService.findByProductIdIn(Set.of(productId1)))
                .thenReturn(List.of(createProduct(productId1)));

        reservationProcessor.processOrderCreated(event);

        verify(reservationService).saveAll(reservationCaptor.capture());
        List<Reservation> reservations = reservationCaptor.getValue();
        assertEquals(1, reservations.size());
        assertEquals(0, reservations.getFirst().getAmount());
    }

    private MessageEvent createOrderCancelledEvent(UUID eventId, Set<UUID> productIds) {
        OrderCancelledData orderData = new OrderCancelledData(productIds, "Customer requested cancellation");
        return new MessageEvent(
                eventId,
                EventType.ORDER_CANCELLED,
                orderId,
                OrderStatus.CANCELLED,
                now,
                orderData
        );
    }

    private MessageEvent createOrderModifiedEvent(UUID eventId, Set<EventItem> itemsToUpdate) {
        OrderModifiedData orderData = new OrderModifiedData(itemsToUpdate);
        return new MessageEvent(
                eventId,
                EventType.ORDER_MODIFIED,
                orderId,
                OrderStatus.MODIFIED,
                now,
                orderData
        );
    }

    @Test
    void processOrderCancelled_successfulRelease() {
        UUID eventId = UUID.randomUUID();
        Set<UUID> productIds = Set.of(productId1, productId2);
        MessageEvent event = createOrderCancelledEvent(eventId, productIds);

        Reservation res1 = new Reservation(UUID.randomUUID(), orderId, productId1, 2, ReservationStatus.RESERVED, now.minusHours(1), null);
        Reservation res2 = new Reservation(UUID.randomUUID(), orderId, productId2, 3, ReservationStatus.RESERVED, now.minusHours(1), null);
        when(reservationService.findAllByOrderIdAndStatusAndProductIdsIn(orderId, ReservationStatus.RESERVED, productIds))
                .thenReturn(List.of(res1, res2));

        Inventory inv1 = createInventory(productId1, 8, 2);
        Inventory inv2 = createInventory(productId2, 7, 3);
        when(inventoryService.findInventoriesByProductIds(productIds))
                .thenReturn(List.of(inv1, inv2));

        reservationProcessor.processOrderCancelled(event);

        verify(reservationService).findAllByOrderIdAndStatusAndProductIdsIn(orderId, ReservationStatus.RESERVED, productIds);
        verify(inventoryService).findInventoriesByProductIds(productIds);

        assertEquals(ReservationStatus.RELEASED, res1.getStatus());
        assertEquals(ReservationStatus.RELEASED, res2.getStatus());
        assertNotNull(res1.getReleasedAt());
        assertNotNull(res2.getReleasedAt());

        assertEquals(10, inv1.getAvailableQuantity());
        assertEquals(0, inv1.getReservedQuantity());
        assertEquals(10, inv2.getAvailableQuantity());
        assertEquals(0, inv2.getReservedQuantity());
    }

    @Test
    void processOrderCancelled_noReservationsFound() {
        UUID eventId = UUID.randomUUID();
        Set<UUID> productIds = Set.of(productId1, productId2);
        MessageEvent event = createOrderCancelledEvent(eventId, productIds);

        when(reservationService.findAllByOrderIdAndStatusAndProductIdsIn(orderId, ReservationStatus.RESERVED, productIds)).thenReturn(List.of());
        reservationProcessor.processOrderCancelled(event);

        verify(reservationService).findAllByOrderIdAndStatusAndProductIdsIn(orderId, ReservationStatus.RESERVED, productIds);
        verify(inventoryService, never()).findInventoriesByProductIds(anySet());
    }

    @Test
    void processOrderCancelled_partialReservationsFound() {
        UUID eventId = UUID.randomUUID();
        Set<UUID> productIds = Set.of(productId1, productId2, UUID.randomUUID());
        MessageEvent event = createOrderCancelledEvent(eventId, productIds);

        Reservation res1 = new Reservation(UUID.randomUUID(), orderId, productId1, 2, ReservationStatus.RESERVED, now.minusHours(1), null);
        when(reservationService.findAllByOrderIdAndStatusAndProductIdsIn(orderId, ReservationStatus.RESERVED, productIds)).thenReturn(List.of(res1));

        Inventory inv1 = createInventory(productId1, 8, 2);
        Inventory inv2 = createInventory(productId2, 10, 0);
        when(inventoryService.findInventoriesByProductIds(productIds)).thenReturn(List.of(inv1, inv2));

        reservationProcessor.processOrderCancelled(event);

        verify(reservationService).findAllByOrderIdAndStatusAndProductIdsIn(orderId, ReservationStatus.RESERVED, productIds);
        verify(inventoryService).findInventoriesByProductIds(productIds);

        assertEquals(ReservationStatus.RELEASED, res1.getStatus());
        assertEquals(10, inv1.getAvailableQuantity());
        assertEquals(0, inv1.getReservedQuantity());
    }

    @Test
    void processOrderCancelled_invalidEventDataType() {
        UUID eventId = UUID.randomUUID();
        MessageEvent event = new MessageEvent(
                eventId,
                EventType.ORDER_CANCELLED,
                orderId,
                OrderStatus.CANCELLED,
                now,
                new OrderCreatedData(UUID.randomUUID(), Currency.USD, BigDecimal.TEN, Set.of())
        );

        reservationProcessor.processOrderCancelled(event);

        verify(reservationService, never()).findAllByOrderIdAndStatusAndProductIdsIn(any(), any(), any());
        verify(inventoryService, never()).findInventoriesByProductIds(anySet());
    }

    @Test
    void processOrderModified_successfulModification() {
        UUID eventId = UUID.randomUUID();
        EventItem item1 = createEventItem(UUID.randomUUID(), productId1, 5);
        EventItem item2 = createEventItem(UUID.randomUUID(), productId2, 3);
        Set<EventItem> itemsToUpdate = Set.of(item1, item2);
        MessageEvent event = createOrderModifiedEvent(eventId, itemsToUpdate);

        Product product1 = createProduct(productId1);
        Product product2 = createProduct(productId2);
        when(productService.findByProductIdIn(Set.of(productId1, productId2))).thenReturn(List.of(product1, product2));

        reservationProcessor.processOrderModified(event);

        verify(reservationModifier).updateReservation(event, itemsToUpdate);
        verify(productService).findByProductIdIn(Set.of(productId1, productId2));
        verify(outboxEventService).persist(outboxEventCaptor.capture());
        verify(eventPublisher).publishEvent(triggerEventCaptor.capture());

        OutboxEvent outboxEvent = outboxEventCaptor.getValue();
        assertEquals(EventType.INVENTORY_RESERVED, outboxEvent.getEventType());
        assertEquals(orderId, outboxEvent.getOrderId());
        assertEquals(PublishmentStatus.NEW, outboxEvent.getStatus());

        PublishmentTriggerEvent triggerEvent = triggerEventCaptor.getValue();
        assertEquals(orderId, triggerEvent.orderId());
    }

    @Test
    void processOrderModified_insufficientInventory() {
        UUID eventId = UUID.randomUUID();
        EventItem item = createEventItem(UUID.randomUUID(), productId1, 100);
        Set<EventItem> itemsToUpdate = Set.of(item);
        MessageEvent event = createOrderModifiedEvent(eventId, itemsToUpdate);

        NotEnoughItemsInInventory exception = new NotEnoughItemsInInventory("Not enough quantity", productId1);
        doThrow(exception).when(reservationModifier).updateReservation(event, itemsToUpdate);

        reservationProcessor.processOrderModified(event);

        verify(reservationModifier).updateReservation(event, itemsToUpdate);
        verify(productService, never()).findByProductIdIn(anySet());
        verify(outboxEventService).persist(outboxEventCaptor.capture());
        verify(eventPublisher).publishEvent(triggerEventCaptor.capture());

        OutboxEvent outboxEvent = outboxEventCaptor.getValue();
        assertEquals(EventType.INVENTORY_FAILED, outboxEvent.getEventType());
        assertEquals(orderId, outboxEvent.getOrderId());
        assertEquals(PublishmentStatus.NEW, outboxEvent.getStatus());
        assertTrue(outboxEvent.getPayload().contains("Not enough requestedItems in inventory!"));
    }

    @Test
    void processOrderModified_invalidEventDataType() {
        UUID eventId = UUID.randomUUID();
        MessageEvent event = new MessageEvent(
                eventId,
                EventType.ORDER_MODIFIED,
                orderId,
                OrderStatus.PENDING,
                now,
                new OrderCreatedData(UUID.randomUUID(), Currency.USD, BigDecimal.TEN, Set.of())
        );

        reservationProcessor.processOrderModified(event);

        verify(reservationModifier, never()).updateReservation(any(), any());
        verify(productService, never()).findByProductIdIn(anySet());
        verify(outboxEventService, never()).persist(any());
        verify(eventPublisher, never()).publishEvent(any());
    }
}