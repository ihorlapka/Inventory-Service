package com.electronics.store.inventory_service.processor;

import com.electronics.store.inventory_service.messaging.message.*;
import com.electronics.store.inventory_service.persistence.mapping.PayloadPatcher;
import com.electronics.store.inventory_service.persistence.model.Inventory;
import com.electronics.store.inventory_service.persistence.model.OutboxEvent;
import com.electronics.store.inventory_service.persistence.model.Product;
import com.electronics.store.inventory_service.persistence.model.Reservation;
import com.electronics.store.inventory_service.persistence.model.enums.EventType;
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
import static java.util.UUID.randomUUID;
import static java.util.stream.Collectors.toSet;
import static java.util.stream.Collectors.toMap;
import static org.hibernate.internal.util.collections.CollectionHelper.setOf;
import static io.micrometer.core.instrument.Timer.Sample;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationProcessor {

    private final InventoryService inventoryService;
    private final ReservationService reservationService;
    private final OutboxEventService outboxEventService;
    private final ApplicationEventPublisher eventPublisher;
    private final ProductService productService;
    private final ReservationModifier reservationModifier;
    private final ReservationMetrics reservationMetrics;

    @Transactional
    public void processOrderCreated(MessageEvent event) {
        final Sample orderCreationTimer = reservationMetrics.startOrderCreatedTimer();
        try {
            final EventData eventData = event.eventData();
            if (!(eventData instanceof OrderCreatedData orderCreatedData)) {
                log.error("EventData is not of type OrderCreatedData {}", event);
                reservationMetrics.getOrderCreatedFailedInvalidEventData().increment();
                return;
            }
            final Set<EventItem> requestedItems = orderCreatedData.items();
            final Set<UUID> productIds = getItemIds(requestedItems);
            final List<Inventory> inventories = inventoryService.findInventoriesByProductIds(productIds);
            final Map<UUID, Inventory> inventoryByProductId = inventories.stream()
                    .collect(toMap(Inventory::getProductId, Function.identity()));

            final UUID outboxEventId = randomUUID();
            final PublishmentTriggerEvent trigger = new PublishmentTriggerEvent(event.orderId());
            if (inventories.size() != productIds.size()) {
                log.warn("Expected to get {}, inventories but found {}", productIds.size(), inventories.size());
                final Set<UUID> missedProducts = getMissedProducts(productIds, inventoryByProductId);
                final String payload = createFailedPayload(event.orderId(), outboxEventId, getUnavailableItems(requestedItems, missedProducts),
                        "No records in db for requested items!");
                persistOutboxEventWithMetrics(event.orderId(), outboxEventId, payload, INVENTORY_FAILED);
                reservationMetrics.getOutboxEventsCreated().increment();
                publishTriggerEvent(trigger);
                reservationMetrics.getOrderCreatedFailedProductNotFound().increment();
                return;
            }
            final Set<UnavailableItem> unavailableItems = getUnavailableItems(requestedItems, inventoryByProductId);
            if (!unavailableItems.isEmpty()) {
                log.warn("Unavailable products found: {}, orderId: {}", unavailableItems, event.orderId());
                final String payload = createFailedPayload(event.orderId(), outboxEventId, unavailableItems, "Not enough items in inventory!");
                persistOutboxEventWithMetrics(event.orderId(), outboxEventId, payload, INVENTORY_FAILED);
                reservationMetrics.getOutboxEventsCreated().increment();
                publishTriggerEvent(trigger);
                reservationMetrics.getOrderCreatedFailedInsufficientStock().increment();
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
            reservationMetrics.getReservationsCreated().increment(reservations.size());
            final List<Product> products = productService.findByProductIdIn(productIds);
            final String payload = createSucceededPayload(event.orderId(), outboxEventId, requestedItems, products);
            persistOutboxEventWithMetrics(event.orderId(), outboxEventId, payload, INVENTORY_RESERVED);
            log.info("Reservations and outbox event were saved successfully for orderId: {}", event.orderId());
            publishTriggerEvent(trigger);
            reservationMetrics.getOrderCreatedProcessed().increment();
        } catch (Exception e) {
            reservationMetrics.getOrderCreatedFailedException().increment();
            throw e;
        } finally {
            reservationMetrics.recordOrderCreatedDuration(orderCreationTimer);
        }
    }

    @Transactional
    public void processOrderCancelled(MessageEvent event) {
        final Sample orderCancellationTimer = reservationMetrics.startOrderCancelledTimer();
        try {
            final EventData eventData = event.eventData();
            if (!(eventData instanceof OrderCancelledData)) {
                log.error("EventData is not of type OrderCancelledData {}", event);
                reservationMetrics.getOrderCancelledFailed().increment();
                return;
            }
            final List<Reservation> reservations = reservationService.findAllByOrderIdAndStatus(event.orderId(), RESERVED);
            if (reservations.isEmpty()) {
                log.warn("No reservations found in db for orderId: {}", event.orderId());
                reservationMetrics.getOrderCancelledFailed().increment();
                return;
            }
            final Map<UUID, Reservation> reservationByProductId = getReservationByProductId(reservations);
            final List<Inventory> inventories = inventoryService.findInventoriesByProductIds(reservationByProductId.keySet());
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
            reservationMetrics.getReservationsReleased().increment(reservations.size());
            log.info("Reservation released for orderId: {}", event.orderId());
            reservationMetrics.getOrderCancelledProcessed().increment();
        } catch (Exception e) {
            reservationMetrics.getOrderCancelledFailed().increment();
            throw e;
        } finally {
            reservationMetrics.recordOrderCancelledDuration(orderCancellationTimer);
        }
    }

    @Transactional
    public void processOrderModified(MessageEvent event) {
        final Sample orderModificationTimer = reservationMetrics.startOrderModifiedTimer();
        try {
            final EventData eventData = event.eventData();
            if (!(eventData instanceof OrderModifiedData(Set<EventItem> itemsToUpdate))) {
                log.error("EventData is not of type OrderModifiedData {}", event);
                reservationMetrics.getOrderModifiedFailed().increment();
                return;
            }
            final UUID outboxEventId = randomUUID();
            try {
                reservationModifier.updateReservation(event, itemsToUpdate);
                final List<Product> products = productService.findByProductIdIn(getItemIds(itemsToUpdate));
                final String payload = createSucceededPayload(event.orderId(), outboxEventId, itemsToUpdate, products);
                persistOutboxEventWithMetrics(event.orderId(), outboxEventId, payload, INVENTORY_RESERVED);
                log.info("Reservations and outbox event were saved successfully for modified reservations orderId: {}", event.orderId());
                reservationMetrics.getOrderModifiedProcessed().increment();
                reservationMetrics.getReservationsModified().increment(itemsToUpdate.size());
            } catch (NotEnoughItemsInInventory e) {
                final Set<UnavailableItem> unavailableItems = getUnavailableItems(itemsToUpdate, setOf(e.getProductId()));
                log.warn("Unavailable product found: {}, orderId: {}", e.getProductId(), event.orderId());
                final String payload = createFailedPayload(event.orderId(), outboxEventId, unavailableItems, "Not enough requestedItems in inventory!");
                persistOutboxEventWithMetrics(event.orderId(), outboxEventId, payload, INVENTORY_FAILED);
                reservationMetrics.getOrderModifiedFailed().increment();
            }
            reservationMetrics.getOutboxEventsCreated().increment();
            publishTriggerEvent(new PublishmentTriggerEvent(event.orderId()));
        } catch (Exception e) {
            reservationMetrics.getOrderModifiedFailed().increment();
            throw e;
        } finally {
            reservationMetrics.recordOrderModifiedDuration(orderModificationTimer);
        }
    }

    private void persistOutboxEventWithMetrics(UUID orderId, UUID outboxEventId, String payload, EventType eventType) {
        final OutboxEvent outboxEvent = new OutboxEvent(outboxEventId, eventType, orderId, now(), payload, NEW, null, 0);
        outboxEventService.persist(outboxEvent);
        log.info("Outbox event persisted: {}", outboxEvent);
    }

    private Set<UnavailableItem> getUnavailableItems(Set<EventItem> requestedItems, Map<UUID, Inventory> inventoryByProductId) {
        final Set<UnavailableItem> unavailableItems = new HashSet<>();
        for (EventItem eventItem : requestedItems) {
            final Inventory inventory = inventoryByProductId.get(eventItem.itemId());
            if (eventItem.quantity() > inventory.getAvailableQuantity()) {
                unavailableItems.add(new UnavailableItem(eventItem.itemId(), eventItem.quantity(), inventory.getAvailableQuantity()));
            }
        }
        return unavailableItems;
    }

    private Set<UUID> getItemIds(Set<EventItem> requestedItemsToUpdate) {
        return requestedItemsToUpdate.stream().map(EventItem::itemId).collect(toSet());
    }

    private Map<UUID, Reservation> getReservationByProductId(List<Reservation> reservations) {
        return reservations.stream().collect(toMap(Reservation::getProductId, Function.identity()));
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

    private String createFailedPayload(UUID orderId, UUID eventId, Set<UnavailableItem> unavailableItems, String reason) {
        return PayloadPatcher.serialize(new MessageEvent(eventId, INVENTORY_FAILED, orderId, RESERVATION_FAILED, now(),
                new InventoryFailedData(unavailableItems, reason)));
    }

    private String createSucceededPayload(UUID orderId, UUID eventId, Set<EventItem> items, List<Product> products) {
        return PayloadPatcher.serialize(new MessageEvent(eventId, INVENTORY_RESERVED, orderId, OrderStatus.RESERVED, now(),
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