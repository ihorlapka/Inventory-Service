package com.electronics.store.inventory_service.processor;

import com.electronics.store.inventory_service.messaging.message.*;
import com.electronics.store.inventory_service.persistence.mapping.PayloadPatcher;
import com.electronics.store.inventory_service.persistence.model.Inventory;
import com.electronics.store.inventory_service.persistence.model.OutboxEvent;
import com.electronics.store.inventory_service.persistence.model.Reservation;
import com.electronics.store.inventory_service.persistence.model.enums.ReservationStatus;
import com.electronics.store.inventory_service.persistence.services.InventoryService;
import com.electronics.store.inventory_service.persistence.services.OutboxEventService;
import com.electronics.store.inventory_service.persistence.services.ReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;

import static com.electronics.store.inventory_service.persistence.model.enums.OrderEventType.INVENTORY_FAILED;
import static com.electronics.store.inventory_service.persistence.model.enums.OrderEventType.INVENTORY_RESERVED;
import static com.electronics.store.inventory_service.persistence.model.enums.OrderStatus.RESERVATION_FAILED;
import static com.electronics.store.inventory_service.persistence.model.enums.OrderStatus.RESERVED;
import static com.electronics.store.inventory_service.persistence.model.enums.PublishmentStatus.NEW;
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

    @Transactional
    public void processOrderCreated(MessageEventIn event) {
        final Set<EventItem> items = event.orderCreatedData().items();
        final Set<UUID> productIds = items.stream().map(EventItem::itemId).collect(toSet());
        final List<Inventory> inventories = inventoryService.findInventoriesByProductIds(productIds);
        final Map<UUID, Inventory> inventoryByProductId = inventories.stream()
                .collect(toMap(Inventory::getProductId, Function.identity()));

        if (inventories.size() != productIds.size()) {
            log.warn("Expected to get {}, inventories but found {}", productIds.size(), inventories.size());
            final Set<UUID> missedProducts = getMissedProducts(productIds, inventoryByProductId);
            final String payload = createFailedPayload(event, getUnavailableItems(items, missedProducts), "No records in db for requested items!");
            final OutboxEvent outboxEvent = new OutboxEvent(null, INVENTORY_FAILED, event.orderId(), now(), payload, NEW, null, 0);
            outboxEventService.persist(outboxEvent);
            log.info("Outbox event persisted: {}", outboxEvent);
            return;
        }
        final Set<UnavailableItem> unavailableItems = new HashSet<>();
        for (EventItem eventItem : items) {
            final Inventory inventory = inventoryByProductId.get(eventItem.itemId());
            if (inventory.getAvailableQuantity() < eventItem.quantity()) {
                unavailableItems.add(new UnavailableItem(eventItem.itemId(), eventItem.quantity(), inventory.getAvailableQuantity()));
            }
        }
        if (!unavailableItems.isEmpty()) {
            log.warn("Unavailable products found: {}, orderId: {}", unavailableItems, event.orderId());
            final String payload = createFailedPayload(event, unavailableItems, "Not enough items in inventory!");
            final OutboxEvent outboxEvent = new OutboxEvent(null, INVENTORY_FAILED, event.orderId(), now(), payload, NEW, null, 0);
            outboxEventService.persist(outboxEvent);
            log.info("Outbox event persisted: {}", outboxEvent);
            return;
        }

        final List<Reservation> reservations = new ArrayList<>(productIds.size());
        for (EventItem eventItem : items) {
            final Inventory inventory = inventoryByProductId.get(eventItem.itemId());
            inventory.setAvailableQuantity(inventory.getAvailableQuantity() - eventItem.quantity());
            inventory.setReservedQuantity(inventory.getReservedQuantity() + eventItem.quantity());
            reservations.add(new Reservation(null, event.orderId(), inventory.getProductId(), eventItem.quantity(), ReservationStatus.RESERVED, now()));
        }
        reservationService.saveAll(reservations);
        final OutboxEvent outboxEvent = new OutboxEvent(null, INVENTORY_RESERVED, event.orderId(), now(),
                createSucceededPayload(event), NEW, null, 0);
        outboxEventService.persist(outboxEvent);
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

    private String createFailedPayload(MessageEventIn event, Set<UnavailableItem> unavailableItems, String reason) {
        return PayloadPatcher.serialize(new MessageEventOut(event.eventId(), INVENTORY_FAILED, event.orderId(), RESERVATION_FAILED, now(),
                new InventoryFailedData(unavailableItems, reason)));
    }

    private String createSucceededPayload(MessageEventIn event) {
        return PayloadPatcher.serialize(new MessageEventOut(event.eventId(), INVENTORY_RESERVED, event.orderId(), RESERVED, now(),
                new InventoryReservedData(getReservedItems(event))));
    }

    private Set<ReservedItem> getReservedItems(MessageEventIn event) {
        return event.orderCreatedData().items().stream()
                .map(item -> new ReservedItem(item.itemId(), item.quantity()))
                .collect(toSet());
    }
}
