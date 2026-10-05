package com.electronics.store.inventory_service.processor;

import com.electronics.store.inventory_service.messaging.message.EventItem;
import com.electronics.store.inventory_service.messaging.message.MessageEvent;
import com.electronics.store.inventory_service.persistence.model.Inventory;
import com.electronics.store.inventory_service.persistence.model.Reservation;
import com.electronics.store.inventory_service.persistence.services.InventoryService;
import com.electronics.store.inventory_service.persistence.services.ReservationService;
import com.google.common.collect.Sets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;

import static com.electronics.store.inventory_service.persistence.model.enums.ReservationStatus.RELEASED;
import static com.electronics.store.inventory_service.persistence.model.enums.ReservationStatus.RESERVED;
import static com.electronics.store.inventory_service.processor.ModificationState.*;
import static java.time.OffsetDateTime.now;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationModifier {

    private final ReservationService reservationService;
    private final InventoryService inventoryService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateReservation(MessageEvent event, Set<EventItem> itemsToUpdate) {
        final Map<UUID, Reservation> reservationByProductId = reservationService.findAllByOrderIdAndStatus(event.orderId(), RESERVED).stream()
                .collect(toMap(Reservation::getProductId, Function.identity()));
        final Set<ModifiedItem> allModifiedItems = getModifiedItems(itemsToUpdate, reservationByProductId);
        final Set<UUID> allProductIds = allModifiedItems.stream().map(ModifiedItem::itemId).collect(toSet());
        final Map<UUID, Inventory> inventoryByProductId = inventoryService.findInventoriesByProductIds(allProductIds).stream()
                .collect(toMap(Inventory::getProductId, Function.identity()));
        final List<Reservation> newReservations = new ArrayList<>();
        for (ModifiedItem modifiedItem : allModifiedItems) {
            final Inventory inventory = inventoryByProductId.get(modifiedItem.itemId());
            switch (modifiedItem.state()) {
                case DELETED -> {
                    final Reservation reservation = reservationByProductId.get(modifiedItem.itemId());
                    log.info("Releasing reservation for productId: {}, because there is not such product in new modified request, orderId: {}",
                            reservation.getProductId(), event.orderId());
                    inventory.setAvailableQuantity(inventory.getAvailableQuantity() + reservation.getAmount());
                    inventory.setReservedQuantity(inventory.getReservedQuantity() - reservation.getAmount());
                    reservation.setStatus(RELEASED);
                    reservation.setReleasedAt(now());
                }
                case NEW -> {
                    if (inventory.getAvailableQuantity() < modifiedItem.newQuantity()) {
                        log.info("Not enough quantity for productId: {}, requested: {}, available: {}",
                                modifiedItem.itemId(), modifiedItem.newQuantity(), inventory.getAvailableQuantity());
                        throw new NotEnoughItemsInInventory("Not enough quantity for productId: ", modifiedItem.itemId());
                    }
                    newReservations.add(new Reservation(null, event.orderId(), modifiedItem.itemId(),
                            modifiedItem.newQuantity(), RESERVED, now(), null));
                    inventory.setAvailableQuantity(inventory.getAvailableQuantity() - modifiedItem.newQuantity());
                    inventory.setReservedQuantity(inventory.getReservedQuantity() + modifiedItem.newQuantity());
                }
                case MODIFIED -> {
                    final Reservation reservation = reservationByProductId.get(modifiedItem.itemId());
                    final int difference = reservation.getAmount() - modifiedItem.newQuantity(); //negative means we need to reserve more
                    if (difference < 0 && inventory.getAvailableQuantity() < Math.abs(difference)) {
                        log.info("Not enough amount for productId: {}, requested: {}, available: {}",
                                modifiedItem.itemId(), modifiedItem.newQuantity(), inventory.getAvailableQuantity());
                        throw new NotEnoughItemsInInventory("Not enough quantity for productId: ", modifiedItem.itemId());
                    }
                    reservation.setAmount(modifiedItem.newQuantity());
                    inventory.setReservedQuantity(inventory.getReservedQuantity() - difference);
                    inventory.setAvailableQuantity(inventory.getAvailableQuantity() + difference);
                }
            }
        }
        reservationService.saveAll(newReservations);
        log.info("Reservations and Inventory were updated successfully orderId: {}", event.orderId());
    }

    private Set<ModifiedItem> getModifiedItems(Set<EventItem> requestedItemsToUpdate, Map<UUID, Reservation> reservationByProductId) {
        final Set<ModifiedItem> addedAndUpdatedItems = requestedItemsToUpdate.stream()
                .map(item -> {
                    final ModificationState state = reservationByProductId.containsKey(item.itemId()) ? MODIFIED : NEW;
                    return new ModifiedItem(item.itemId(), state, item.quantity());
                })
                .collect(toSet());

        final Set<UUID> newItemIds = requestedItemsToUpdate.stream().map(EventItem::itemId).collect(toSet());
        final Set<ModifiedItem> removedItems = reservationByProductId.keySet().stream()
                .filter(itemId -> !newItemIds.contains(itemId))
                .map(itemId -> new ModifiedItem(itemId, DELETED, 0))
                .collect(toSet());

        return Sets.union(addedAndUpdatedItems, removedItems);
    }
}
