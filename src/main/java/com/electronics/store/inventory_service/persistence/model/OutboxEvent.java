package com.electronics.store.inventory_service.persistence.model;

import com.electronics.store.inventory_service.persistence.model.enums.OrderEventType;
import com.electronics.store.inventory_service.persistence.model.enums.PublishmentStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@ToString
@Getter
@Setter
@Table(name = "outbox_events")
@NoArgsConstructor
@AllArgsConstructor
public class OutboxEvent {

    @Id
    @Column(columnDefinition = "uuid", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "event_type", nullable = false, updatable = false)
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private OrderEventType eventType;

    @Column(updatable = false, nullable = false)
    private UUID orderId;

    @Column(name = "created_at", columnDefinition = "TIMESTAMP WITH TIME ZONE", updatable = false)
    private OffsetDateTime createdAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb")
    private String payload;

    @Column(name = "status", nullable = false, updatable = false)
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private PublishmentStatus status;

    @Column(name = "published_at", columnDefinition = "TIMESTAMP WITH TIME ZONE", updatable = false)
    private OffsetDateTime publishedAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;
}
