package com.electronics.store.inventory_service.health;

import com.electronics.store.inventory_service.persistence.model.OutboxEvent;
import com.electronics.store.inventory_service.persistence.model.enums.PublishmentStatus;
import com.electronics.store.inventory_service.persistence.repositories.OutboxEventRepository;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

import static java.time.OffsetDateTime.now;

@Component
public class InventoryHealthIndicator implements HealthIndicator {

    private final JdbcTemplate jdbcTemplate;
    private final CachingConnectionFactory connectionFactory;
    private final OutboxEventRepository outboxEventRepository;
    private final long pendingOutboxEventsThreshold;
    private final long staleOutboxEventsThresholdMinutes;

    public InventoryHealthIndicator(JdbcTemplate jdbcTemplate,
                                     CachingConnectionFactory connectionFactory,
                                     OutboxEventRepository outboxEventRepository,
                                     @Value("${inventory.health.pending-outbox-events-threshold:1000}") long pendingOutboxEventsThreshold,
                                     @Value("${inventory.health.stale-outbox-events-threshold-minutes:30}") long staleOutboxEventsThresholdMinutes) {
        this.jdbcTemplate = jdbcTemplate;
        this.connectionFactory = connectionFactory;
        this.outboxEventRepository = outboxEventRepository;
        this.pendingOutboxEventsThreshold = pendingOutboxEventsThreshold;
        this.staleOutboxEventsThresholdMinutes = staleOutboxEventsThresholdMinutes;
    }

    @Override
    public Health health() {
        Health.Builder healthBuilder = Health.up();
        checkDatabase(healthBuilder);
        checkRabbitMq(healthBuilder);
        checkOutboxEvents(healthBuilder);
        return healthBuilder.build();
    }

    private void checkDatabase(Health.Builder builder) {
        try {
            final Long count = jdbcTemplate.queryForObject("SELECT 1", Long.class);
            if (count != null && count == 1) {
                builder.withDetail("database", "UP")
                        .withDetail("database.check", "SELECT 1 successful");
            } else {
                builder.down()
                        .withDetail("database", "DOWN")
                        .withDetail("database.check", "Unexpected result from SELECT 1");
            }
        } catch (Exception e) {
            builder.down()
                    .withDetail("database", "DOWN")
                    .withDetail("database.error", e.getMessage());
        }
    }

    private void checkRabbitMq(Health.Builder builder) {
        try {
            final Connection rabbitConnection = connectionFactory.createConnection();
            boolean isOpen = rabbitConnection.isOpen();
            rabbitConnection.close();
            if (isOpen) {
                builder.withDetail("rabbitmq", "UP")
                        .withDetail("rabbitmq.connection", "OPEN");
            } else {
                builder.down()
                        .withDetail("rabbitmq", "DOWN")
                        .withDetail("rabbitmq.connection", "CLOSED");
            }
        } catch (Exception e) {
            builder.down()
                    .withDetail("rabbitmq", "DOWN")
                    .withDetail("rabbitmq.error", e.getMessage());
        }
    }

    private void checkOutboxEvents(Health.Builder builder) {
        try {
            final long pendingCount = outboxEventRepository.countByStatus(PublishmentStatus.NEW);
            if (pendingCount > pendingOutboxEventsThreshold) {
                builder.down()
                        .withDetail("outbox.pendingEvents", "HIGH")
                        .withDetail("outbox.pendingEvents.count", pendingCount)
                        .withDetail("outbox.pendingEvents.threshold", pendingOutboxEventsThreshold);
            } else {
                builder.withDetail("outbox.pendingEvents", "OK")
                        .withDetail("outbox.pendingEvents.count", pendingCount)
                        .withDetail("outbox.pendingEvents.threshold", pendingOutboxEventsThreshold);
            }
            final OffsetDateTime staleThreshold = now().minusMinutes(staleOutboxEventsThresholdMinutes);
            final List<OutboxEvent> staleEvents = outboxEventRepository.findStaleEvents(staleThreshold, (int) staleOutboxEventsThresholdMinutes);
            if (!staleEvents.isEmpty()) {
                builder.down()
                        .withDetail("outbox.staleEvents", "FOUND")
                        .withDetail("outbox.staleEvents.count", staleEvents.size())
                        .withDetail("outbox.staleEvents.thresholdMinutes", staleOutboxEventsThresholdMinutes);
            } else {
                builder.withDetail("outbox.staleEvents", "NONE")
                        .withDetail("outbox.staleEvents.thresholdMinutes", staleOutboxEventsThresholdMinutes);
            }
        } catch (Exception e) {
            builder.down()
                    .withDetail("outbox", "ERROR")
                    .withDetail("outbox.error", e.getMessage());
        }
    }
}