package com.electronics.store.inventory_service.persistence.services;

import com.electronics.store.inventory_service.persistence.model.Reservation;
import com.electronics.store.inventory_service.persistence.repositories.ReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ReservationService {

    private final ReservationRepository reservationRepository;

    public void saveAll(List<Reservation> reservations) {
        reservationRepository.saveAll(reservations);
    }
}
