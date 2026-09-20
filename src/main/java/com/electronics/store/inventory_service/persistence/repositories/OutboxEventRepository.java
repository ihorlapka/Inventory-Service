package com.electronics.store.inventory_service.persistence.repositories;

import com.electronics.store.inventory_service.persistence.model.OutboxEvent;
import jakarta.persistence.LockModeType;
import lombok.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    Optional<OutboxEvent> findByOrderId(UUID orderId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM OrderEvent oe WHERE oe.orderId = :orderId")
    int removeByOrderId(@NonNull @Param("orderId") UUID orderId);

    @Query(value = """
            SELECT * FROM order_events
            WHERE status = 'NEW'
            ORDER BY created_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> findFreshEventsForUpdate(@Param("batchSize") int batchSize);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE order_events
            SET status = 'PUBLISHED'
            WHERE id IN (:publishedIds)
            """, nativeQuery = true)
    int updatePublishedEvents(@Param("publishedIds") List<UUID> publishedIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT e FROM OrderEvent e
            WHERE e.orderId = :orderId
            ORDER BY e.createdAt DESC
            LIMIT 1
            """)
    Optional<OutboxEvent> findLastByOrderIdForUpdate(@Param("orderId") UUID orderId);

    List<OutboxEvent> findAllByOrderId(UUID orderId);
}
