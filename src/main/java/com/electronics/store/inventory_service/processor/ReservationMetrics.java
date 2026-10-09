package com.electronics.store.inventory_service.processor;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.Getter;
import org.springframework.stereotype.Component;

@Component
public class ReservationMetrics {

    private final MeterRegistry meterRegistry;

    @Getter
    private final Counter orderCreatedProcessed;
    @Getter
    private final Counter orderCreatedFailedInvalidEventData;
    @Getter
    private final Counter orderCreatedFailedProductNotFound;
    @Getter
    private final Counter orderCreatedFailedInsufficientStock;
    @Getter
    private final Counter orderCreatedFailedException;
    @Getter
    private final Counter orderCancelledProcessed;
    @Getter
    private final Counter orderCancelledFailed;
    @Getter
    private final Counter orderModifiedProcessed;
    @Getter
    private final Counter orderModifiedFailed;

    @Getter
    private final Counter reservationsCreated;
    @Getter
    private final Counter reservationsReleased;
    @Getter
    private final Counter reservationsModified;

    @Getter
    private final Counter outboxEventsCreated;
    @Getter
    private final Counter outboxEventsPublished;
    @Getter
    private final Counter outboxEventsFailed;

    @Getter
    private final Timer orderCreatedProcessingTimer;
    @Getter
    private final Timer orderCancelledProcessingTimer;
    @Getter
    private final Timer orderModifiedProcessingTimer;

    public ReservationMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.orderCreatedProcessed = Counter.builder("inventory_reservation_order_created_processed")
                .description("Total number of ORDER_CREATED events processed successfully")
                .register(meterRegistry);

        this.orderCreatedFailedInvalidEventData = Counter.builder("inventory_reservation_order_created_failed")
                .description("Total number of ORDER_CREATED events that failed due to invalid event data")
                .tag("reason", "invalid_event_data")
                .register(meterRegistry);

        this.orderCreatedFailedProductNotFound = Counter.builder("inventory_reservation_order_created_failed")
                .description("Total number of ORDER_CREATED events that failed due to product not found")
                .tag("reason", "product_not_found")
                .register(meterRegistry);

        this.orderCreatedFailedInsufficientStock = Counter.builder("inventory_reservation_order_created_failed")
                .description("Total number of ORDER_CREATED events that failed due to insufficient stock")
                .tag("reason", "insufficient_stock")
                .register(meterRegistry);

        this.orderCreatedFailedException = Counter.builder("inventory_reservation_order_created_failed")
                .description("Total number of ORDER_CREATED events that failed due to exception")
                .tag("reason", "exception")
                .register(meterRegistry);

        this.orderCancelledProcessed = Counter.builder("inventory_reservation_order_cancelled_processed")
                .description("Total number of ORDER_CANCELLED events processed successfully")
                .register(meterRegistry);

        this.orderCancelledFailed = Counter.builder("inventory_reservation_order_cancelled_failed")
                .description("Total number of ORDER_CANCELLED events that failed")
                .register(meterRegistry);

        this.orderModifiedProcessed = Counter.builder("inventory_reservation_order_modified_processed")
                .description("Total number of ORDER_MODIFIED events processed successfully")
                .register(meterRegistry);

        this.orderModifiedFailed = Counter.builder("inventory_reservation_order_modified_failed")
                .description("Total number of ORDER_MODIFIED events that failed")
                .register(meterRegistry);

        this.reservationsCreated = Counter.builder("inventory_reservations_created")
                .description("Total number of reservations created")
                .register(meterRegistry);

        this.reservationsReleased = Counter.builder("inventory_reservations_released")
                .description("Total number of reservations released")
                .register(meterRegistry);

        this.reservationsModified = Counter.builder("inventory_reservations_modified")
                .description("Total number of reservations modified")
                .register(meterRegistry);

        this.outboxEventsCreated = Counter.builder("inventory_outbox_events_created")
                .description("Total number of outbox events created")
                .register(meterRegistry);

        this.outboxEventsPublished = Counter.builder("inventory_outbox_events_published")
                .description("Total number of outbox events published")
                .register(meterRegistry);

        this.outboxEventsFailed = Counter.builder("inventory_outbox_events_failed")
                .description("Total number of outbox events that failed")
                .register(meterRegistry);

        this.orderCreatedProcessingTimer = Timer.builder("inventory_reservation_order_created_processing_duration")
                .description("Time taken to process ORDER_CREATED event")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(meterRegistry);

        this.orderCancelledProcessingTimer = Timer.builder("inventory_reservation_order_cancelled_processing_duration")
                .description("Time taken to process ORDER_CANCELLED event")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(meterRegistry);

        this.orderModifiedProcessingTimer = Timer.builder("inventory_reservation_order_modified_processing_duration")
                .description("Time taken to process ORDER_MODIFIED event")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    public Timer.Sample startOrderCreatedTimer() {
        return Timer.start(meterRegistry);
    }

    public Timer.Sample startOrderCancelledTimer() {
        return Timer.start(meterRegistry);
    }

    public Timer.Sample startOrderModifiedTimer() {
        return Timer.start(meterRegistry);
    }

    public void recordOrderCreatedDuration(Timer.Sample sample) {
        sample.stop(orderCreatedProcessingTimer);
    }

    public void recordOrderCancelledDuration(Timer.Sample sample) {
        sample.stop(orderCancelledProcessingTimer);
    }

    public void recordOrderModifiedDuration(Timer.Sample sample) {
        sample.stop(orderModifiedProcessingTimer);
    }
}