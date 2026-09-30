package com.electronics.store.inventory_service.outbox;

import com.electronics.store.inventory_service.persistence.model.OutboxEvent;
import com.electronics.store.inventory_service.persistence.services.OutboxEventService;
import com.electronics.store.outbox_event_publisher.OutboxEventHandler;
import com.electronics.store.outbox_event_publisher.OutboxEventManager;
import com.electronics.store.outbox_event_publisher.OutboxProcessor;
import com.electronics.store.outbox_event_publisher.rabbit.RabbitMqConfig;
import com.electronics.store.outbox_event_publisher.rabbit.RabbitMqPublisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Import(RabbitMqConfig.class)
@Configuration
public class OutboxConfig {

    @Bean
    public OutboxEventHandler<OutboxEvent> outboxEventHandler(OutboxEventManager<OutboxEvent> outboxEventManager) {
        return new OutboxEventHandler<>(outboxEventManager);
    }

    @Bean
    public OutboxEventManager<OutboxEvent> outboxEventManager(OutboxProcessor<OutboxEvent> outboxProcessor,
                                                              @Value("${outbox.timeout.after.failure.ms:5000}") int timeoutAfterFailureMs) {
        return new OutboxEventManager<>(outboxProcessor, timeoutAfterFailureMs);
    }

    @Bean
    public OutboxProcessor<OutboxEvent> outboxProcessor(RabbitMqPublisher rabbitMqPublisher,
                                           OutboxEventService outboxEventService,
                                           @Value("${outbox.events.batch.size}") int eventsBatchSize) {
        return new OutboxProcessor<>(rabbitMqPublisher, outboxEventService, eventsBatchSize);
    }
}
