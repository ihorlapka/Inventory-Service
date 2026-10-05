package com.electronics.store.inventory_service.persistence.services;

import com.electronics.store.inventory_service.persistence.model.Reservation;
import com.electronics.store.inventory_service.persistence.model.enums.ReservationStatus;
import com.electronics.store.inventory_service.persistence.repositories.ReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReservationService {

    private final ReservationRepository reservationRepository;

    public void saveAll(List<Reservation> reservations) {
        reservationRepository.saveAll(reservations);
    }

    public List<Reservation> findAllByOrderIdAndStatusAndProductIdsIn(UUID orderId, ReservationStatus status, Set<UUID> productIds) {
        return reservationRepository.findAllByOrderIdAndStatusAndProductIdsIn(orderId, status, productIds);
    }

    public void updateStatusForProductIdsIn(UUID orderId, ReservationStatus newStatus, ReservationStatus currentStatus, Set<UUID> productIds) {
        reservationRepository.updateStatusForProductIdsIn(orderId, newStatus, currentStatus, productIds);
    }

    public List<Reservation> findAllByOrderIdAndStatus(UUID orderId, ReservationStatus status) {
        return reservationRepository.findAllByOrderIdAndStatus(orderId, status);
    }
}
