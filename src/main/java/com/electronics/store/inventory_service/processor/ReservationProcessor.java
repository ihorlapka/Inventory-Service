package com.electronics.store.inventory_service.processor;

import com.electronics.store.inventory_service.messaging.message.*;
import com.electronics.store.inventory_service.persistence.mapping.PayloadPatcher;
import com.electronics.store.inventory_service.persistence.model.Inventory;
import com.electronics.store.inventory_service.persistence.model.OutboxEvent;
import com.electronics.store.inventory_service.persistence.model.Product;
import com.electronics.store.inventory_service.persistence.model.Reservation;
import com.electronics.store.inventory_service.persistence.model.enums.OrderStatus;
import com.electronics.store.inventory_service.persistence.services.InventoryService;
import com.electronics.store.inventory_service.persistence.services.OutboxEventService;
import com.electronics.store.inventory_service.persistence.services.ProductService;
import com.electronics.store.inventory_service.persistence.services.ReservationService;
import com.electronics.store.outbox_event_publisher.PublishmentTriggerEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;

import static com.electronics.store.inventory_service.persistence.model.enums.EventType.INVENTORY_FAILED;
import static com.electronics.store.inventory_service.persistence.model.enums.EventType.INVENTORY_RESERVED;
import static com.electronics.store.inventory_service.persistence.model.enums.OrderStatus.RESERVATION_FAILED;
import static com.electronics.store.inventory_service.persistence.model.enums.PublishmentStatus.NEW;
import static com.electronics.store.inventory_service.persistence.model.enums.ReservationStatus.RELEASED;
import static com.electronics.store.inventory_service.persistence.model.enums.ReservationStatus.RESERVED;
import static java.time.OffsetDateTime.now;
import static java.util.stream.Collectors.toSet;
import static java.util.stream.Collectors.toMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationProcessor {

    private final InventoryService inventoryService;
    private final ReservationService reservationService;
    private final OutboxEventService outboxEventService;
    private final ApplicationEventPublisher eventPublisher;
    private final ProductService productService;

    @Transactional
    public void processOrderCreated(MessageEvent event) {
        final EventData eventData = event.eventData();
        if (!(eventData instanceof OrderCreatedData orderCreatedData)) {
            log.error("EventData is not of type OrderCreatedData {}", event);
            return;
        }
        final Set<EventItem> requestedItems = orderCreatedData.items();
        final Set<UUID> productIds = requestedItems.stream().map(EventItem::itemId).collect(toSet());
        final List<Inventory> inventories = inventoryService.findInventoriesByProductIds(productIds);
        final Map<UUID, Inventory> inventoryByProductId = inventories.stream()
                .collect(toMap(Inventory::getProductId, Function.identity()));

        final PublishmentTriggerEvent trigger = new PublishmentTriggerEvent(event.orderId());
        if (inventories.size() != productIds.size()) {
            log.warn("Expected to get {}, inventories but found {}", productIds.size(), inventories.size());
            final Set<UUID> missedProducts = getMissedProducts(productIds, inventoryByProductId);
            final String payload = createFailedPayload(event, getUnavailableItems(requestedItems, missedProducts), "No records in db for requested requestedItems!");
            final OutboxEvent outboxEvent = new OutboxEvent(null, INVENTORY_FAILED, event.orderId(), now(), payload, NEW, null, 0);
            outboxEventService.persist(outboxEvent);
            log.info("Outbox event persisted: {}", outboxEvent);
            publishTriggerEvent(trigger);
            return;
        }
        final Set<UnavailableItem> unavailableItems = new HashSet<>();
        for (EventItem eventItem : requestedItems) {
            final Inventory inventory = inventoryByProductId.get(eventItem.itemId());
            if (eventItem.quantity() > inventory.getAvailableQuantity()) {
                unavailableItems.add(new UnavailableItem(eventItem.itemId(), eventItem.quantity(), inventory.getAvailableQuantity()));
            }
        }
        if (!unavailableItems.isEmpty()) {
            log.warn("Unavailable products found: {}, orderId: {}", unavailableItems, event.orderId());
            final String payload = createFailedPayload(event, unavailableItems, "Not enough requestedItems in inventory!");
            final OutboxEvent outboxEvent = new OutboxEvent(null, INVENTORY_FAILED, event.orderId(), now(), payload, NEW, null, 0);
            outboxEventService.persist(outboxEvent);
            log.info("Outbox event persisted: {}", outboxEvent);
            publishTriggerEvent(trigger);
            return;
        }

        final List<Reservation> reservations = new ArrayList<>(productIds.size());
        for (EventItem eventItem : requestedItems) {
            final Inventory inventory = inventoryByProductId.get(eventItem.itemId());
            inventory.setAvailableQuantity(inventory.getAvailableQuantity() - eventItem.quantity());
            inventory.setReservedQuantity(inventory.getReservedQuantity() + eventItem.quantity());
            reservations.add(new Reservation(null, event.orderId(), inventory.getProductId(), eventItem.quantity(), RESERVED, now(), null));
        }
        reservationService.saveAll(reservations);
        final List<Product> products = productService.findByProductIdIn(productIds);
        final OutboxEvent outboxEvent = new OutboxEvent(null, INVENTORY_RESERVED, event.orderId(), now(),
                createSucceededPayload(event, requestedItems, products), NEW, null, 0);
        outboxEventService.persist(outboxEvent);
        log.info("Reservations and outbox event were saved successfully for orderId: {}", event.orderId());
        publishTriggerEvent(trigger);
    }

    @Transactional
    public void processOrderCancelled(MessageEvent event) {
        final EventData eventData = event.eventData();
        if (!(eventData instanceof OrderCancelledData orderCancelledData)) {
            log.error("EventData is not of type OrderCancelledData {}", event);
            return;
        }
        final List<Reservation> reservations = reservationService.findAllByOrderIdAndStatusAndProductIdsIn(event.orderId(), RESERVED, orderCancelledData.productIds());
        if (reservations.isEmpty()) {
            log.warn("No reservations found in db for orderId: {}, requested items {}!", event.orderId(), orderCancelledData.productIds());
            return;
        }
        final List<Inventory> inventories = inventoryService.findInventoriesByProductIds(orderCancelledData.productIds());
        final Map<UUID, Reservation> reservationByProductId = reservations.stream().collect(toMap(Reservation::getProductId, Function.identity()));
        for (Inventory inventory : inventories) {
            final Reservation reservation = reservationByProductId.get(inventory.getProductId());
            if (reservation == null) {
                log.warn("No matching reservation for productId: {} in orderId: {}, skipping inventory update", inventory.getProductId(), event.orderId());
                continue;
            }
            inventory.setAvailableQuantity(inventory.getAvailableQuantity() + reservation.getAmount());
            inventory.setReservedQuantity(inventory.getReservedQuantity() - reservation.getAmount());
        }
        for (Reservation reservation : reservations) {
            reservation.setStatus(RELEASED);
            reservation.setReleasedAt(now());
        }
        log.info("Reservation released for orderId: {}", event.orderId());
    }

    @Transactional
    public void processOrderModified(MessageEvent event) {
        final EventData eventData = event.eventData();
        if (!(eventData instanceof OrderModifiedData orderModifiedData)) {
            log.error("EventData is not of type OrderModifiedData {}", event);
            return;
        }

    }

    private Set<UUID> getMissedProducts(Set<UUID> productIds, Map<UUID, Inventory> inventoryByProductId) {
        return productIds.stream()
                .filter(productId -> !inventoryByProductId.containsKey(productId))
                .collect(toSet());
    }

    private Set<UnavailableItem> getUnavailableItems(Set<EventItem> items, Set<UUID> missedProducts) {
        return items.stream()
                .filter(item -> missedProducts.contains(item.itemId()))
                .map(item -> new UnavailableItem(item.itemId(), item.quantity(), 0))
                .collect(toSet());
    }

    private String createFailedPayload(MessageEvent event, Set<UnavailableItem> unavailableItems, String reason) {
        return PayloadPatcher.serialize(new MessageEvent(event.eventId(), INVENTORY_FAILED, event.orderId(), RESERVATION_FAILED, now(),
                new InventoryFailedData(unavailableItems, reason)));
    }

    private String createSucceededPayload(MessageEvent event, Set<EventItem> items, List<Product> products) {
        return PayloadPatcher.serialize(new MessageEvent(event.eventId(), INVENTORY_RESERVED, event.orderId(), OrderStatus.RESERVED, now(),
                new InventoryReservedData(getReservedItems(items, products))));
    }

    private Set<ReservedItem> getReservedItems(Set<EventItem> items, List<Product> products) {
        final Map<UUID, Product> productById = products.stream().collect(toMap(Product::getId, Function.identity()));
        return items.stream()
                .map(item -> {
                    final Product product = productById.get(item.itemId());
                    return new ReservedItem(item.itemId(), item.quantity(), product.getPrice(), product.getDescription(), product.getImageUrl());
                })
                .collect(toSet());
    }

    private void publishTriggerEvent(PublishmentTriggerEvent applicationEvent) {
        log.info("Sending application event: {}", applicationEvent);
        eventPublisher.publishEvent(applicationEvent);
    }
}
