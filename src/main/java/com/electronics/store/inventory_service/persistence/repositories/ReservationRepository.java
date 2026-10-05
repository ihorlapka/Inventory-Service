package com.electronics.store.inventory_service.persistence.repositories;

import com.electronics.store.inventory_service.persistence.model.Reservation;
import com.electronics.store.inventory_service.persistence.model.enums.ReservationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Repository
public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    List<Reservation> findAllByOrderId(UUID orderId);

    List<Reservation> findAllByOrderIdIn(List<UUID> orderIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT r FROM Reservation r
            WHERE r.status = :status AND r.orderId = :orderId AND r.productId IN (:productIds)
            ORDER BY r.productId
            """)
    List<Reservation> findAllByOrderIdAndStatusAndProductIdsIn(@Param("orderId") UUID orderId,
                                                               @Param("status") ReservationStatus status,
                                                               @Param("productIds") Set<UUID> productIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT r FROM Reservation r
            WHERE r.status = :status AND r.orderId = :orderId
            ORDER BY r.productId
            """)
    List<Reservation> findAllByOrderIdAndStatus(@Param("orderId") UUID orderId,
                                                @Param("status") ReservationStatus status);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Reservation r
            SET r.status = :newStatus
            WHERE r.orderId = :orderId AND r.status = :currentStatus AND r.productId IN (:productIds)
            """)
    void updateStatusForProductIdsIn(@Param("orderId") UUID orderId,
                                     @Param("status") ReservationStatus newStatus,
                                     @Param("status") ReservationStatus currentStatus,
                                     @Param("productIds") Set<UUID> productIds);
}
