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
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public void processOrderCreated(MessageEvent event) {
        final EventData eventData = event.eventData();
        if (!(eventData instanceof OrderCreatedData)) {
            log.error("EventData is not of type OrderCreatedData {}", event);
            return;
        }
        final Set<EventItem> items = ((OrderCreatedData) eventData).items();
        final Set<UUID> productIds = items.stream().map(EventItem::itemId).collect(toSet());
        final List<Inventory> inventories = inventoryService.findInventoriesByProductIds(productIds);
        final Map<UUID, Inventory> inventoryByProductId = inventories.stream()
                .collect(toMap(Inventory::getProductId, Function.identity()));

        final PublishmentTriggerEvent trigger = new PublishmentTriggerEvent(event.orderId());
        if (inventories.size() != productIds.size()) {
            log.warn("Expected to get {}, inventories but found {}", productIds.size(), inventories.size());
            final Set<UUID> missedProducts = getMissedProducts(productIds, inventoryByProductId);
            final String payload = createFailedPayload(event, getUnavailableItems(items, missedProducts), "No records in db for requested items!");
            final OutboxEvent outboxEvent = new OutboxEvent(null, INVENTORY_FAILED, event.orderId(), now(), payload, NEW, null, 0);
            outboxEventService.persist(outboxEvent);
            log.info("Outbox event persisted: {}", outboxEvent);
            publishTriggerEvent(trigger);
            return;
        }
        final Set<UnavailableItem> unavailableItems = new HashSet<>();
        for (EventItem eventItem : items) {
            final Inventory inventory = inventoryByProductId.get(eventItem.itemId());
            if (eventItem.quantity() > inventory.getAvailableQuantity()) {
                unavailableItems.add(new UnavailableItem(eventItem.itemId(), eventItem.quantity(), inventory.getAvailableQuantity()));
            }
        }
        if (!unavailableItems.isEmpty()) {
            log.warn("Unavailable products found: {}, orderId: {}", unavailableItems, event.orderId());
            final String payload = createFailedPayload(event, unavailableItems, "Not enough items in inventory!");
            final OutboxEvent outboxEvent = new OutboxEvent(null, INVENTORY_FAILED, event.orderId(), now(), payload, NEW, null, 0);
            outboxEventService.persist(outboxEvent);
            log.info("Outbox event persisted: {}", outboxEvent);
            publishTriggerEvent(trigger);
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
                createSucceededPayload(event, items), NEW, null, 0);
        outboxEventService.persist(outboxEvent);
        log.info("Reservations and outbox event were saved successfully for orderId: {}", event.orderId());
        publishTriggerEvent(trigger);
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

    private String createSucceededPayload(MessageEvent event, Set<EventItem> items) {
        return PayloadPatcher.serialize(new MessageEvent(event.eventId(), INVENTORY_RESERVED, event.orderId(), RESERVED, now(),
                new InventoryReservedData(getReservedItems(items))));
    }

    private Set<ReservedItem> getReservedItems(Set<EventItem> items) {
        return items.stream()
                .map(item -> new ReservedItem(item.itemId(), item.quantity()))
                .collect(toSet());
    }

    private void publishTriggerEvent(PublishmentTriggerEvent applicationEvent) {
        log.info("Sending application event: {}", applicationEvent);
        eventPublisher.publishEvent(applicationEvent);
    }
}
