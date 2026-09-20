package com.electronics.store.inventory_service.persistence.services;

import com.electronics.store.inventory_service.persistence.model.OutboxEvent;
import com.electronics.store.inventory_service.persistence.repositories.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OutboxEventService {

    private final OutboxEventRepository eventRepository;

    public Optional<OutboxEvent> findByOrderId(UUID orderId) {
        return eventRepository.findByOrderId(orderId);
    }

    public OutboxEvent persist(OutboxEvent event) {
        return eventRepository.save(event);
    }

    public int removeByOrderId(UUID orderId) {
        return eventRepository.removeByOrderId(orderId);
    }

    public List<OutboxEvent> findFreshEventsForUpdate(int batchSize) {
        return eventRepository.findFreshEventsForUpdate(batchSize);
    }

    public int updatePublishedEvents(List<UUID> publishedIds) {
        return eventRepository.updatePublishedEvents(publishedIds);
    }

    public Optional<OutboxEvent> findLastByOrderIdForUpdate(UUID orderId) {
        return eventRepository.findLastByOrderIdForUpdate(orderId);
    }

    public List<OutboxEvent> findAllByOrderId(UUID orderId) {
        return eventRepository.findAllByOrderId(orderId);
    }
}
