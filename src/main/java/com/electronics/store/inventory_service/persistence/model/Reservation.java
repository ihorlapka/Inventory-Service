package com.electronics.store.inventory_service.persistence.model;

import com.electronics.store.inventory_service.persistence.model.enums.ReservationStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Getter
@Setter
@ToString
@Table(name = "reservations", uniqueConstraints = @UniqueConstraint(name = "uk_reservation_order_product", columnNames = {"order_id", "product_id"}))
@NoArgsConstructor
@AllArgsConstructor
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "amount", nullable = false)
    private int amount;

    @Column(name = "status", nullable = false, updatable = false)
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private ReservationStatus status;

    @Column(name = "created_at", columnDefinition = "TIMESTAMP WITH TIME ZONE", updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "released_at", columnDefinition = "TIMESTAMP WITH TIME ZONE")
    private OffsetDateTime releasedAt;
}
