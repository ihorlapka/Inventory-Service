package com.electronics.store.inventory_service.messaging;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.Getter;
import org.springframework.stereotype.Component;

@Component
public class RabbitMqMetrics {

    private final MeterRegistry meterRegistry;

    @Getter
    private final Counter messagesProcessedSuccess;
    @Getter
    private final Counter invalidMessagesTotal;
    @Getter
    private final Timer messagePublishTimer;

    public RabbitMqMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;

        this.messagesProcessedSuccess = Counter.builder("inventory_rabbitmq_messages_processed_success")
                .description("Total number of messages processed successfully")
                .tag("queue", "orders-created")
                .register(meterRegistry);

        this.invalidMessagesTotal = Counter.builder("inventory_rabbitmq_invalid_messages_total")
                .description("Total number of invalid messages sent to DLQ")
                .tag("queue", "orders-created")
                .register(meterRegistry);

        this.messagePublishTimer = Timer.builder("inventory_rabbitmq_message_publish_duration")
                .description("Time taken to publish a message to RabbitMQ")
                .tag("exchange", "es.inventory.events.exchange")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    public Timer.Sample startPublishTimer() {
        return Timer.start(meterRegistry);
    }

    public void recordMessagePublishDuration(Timer.Sample sample) {
        sample.stop(messagePublishTimer);
    }
}