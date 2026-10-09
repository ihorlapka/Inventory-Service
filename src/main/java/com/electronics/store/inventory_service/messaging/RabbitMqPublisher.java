package com.electronics.store.inventory_service.messaging;

import com.electronics.store.inventory_service.messaging.message.MessageEvent;
import com.electronics.store.inventory_service.persistence.model.enums.EventType;
import com.electronics.store.outbox_event_publisher.EventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.MessagePropertiesBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

import static io.micrometer.core.instrument.Timer.Sample;
import static com.electronics.store.inventory_service.persistence.model.enums.EventType.INVENTORY_RESERVED;

@Slf4j
@Component
@RequiredArgsConstructor
public class RabbitMqPublisher implements EventPublisher {

    private static final String TYPE_ID = "__TypeId__";

    private final RabbitTemplate rabbitTemplate;
    private final RabbitMqProperties rabbitProps;
    private final RabbitMqMetrics rabbitMqMetrics;

    @Override
    public void publish(UUID orderId, UUID eventId, String eventTypeName, String payload) {
        final String routingKey = getRoutingKey(eventTypeName);
        final Sample publishmentStart = rabbitMqMetrics.startPublishTimer();
        try {
            log.info("Sending message for orderId: {}, {}", orderId, payload);
            final MessageProperties properties = MessagePropertiesBuilder.newInstance()
                    .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                    .setHeader(TYPE_ID, MessageEvent.class.getName())
                    .setMessageId(eventId.toString())
                    .setCorrelationId(orderId.toString())
                    .setTimestamp(new Date())
                    .build();
            final Message message = new Message(payload.getBytes(StandardCharsets.UTF_8), properties);
            rabbitTemplate.send(rabbitProps.getInventoryExchange(), routingKey, message);
            log.info("Message sent for orderId: {}", orderId);
        } catch (AmqpException e) {
            log.error("Failed to send message to exchange={}, routingKey={}", rabbitProps.getInventoryExchange(), routingKey, e);
            throw e;
        } finally {
            rabbitMqMetrics.recordMessagePublishDuration(publishmentStart);
        }
    }

    private String getRoutingKey(String eventTypeName) {
        try {
            final EventType eventType = EventType.valueOf(eventTypeName);
            return INVENTORY_RESERVED.equals(eventType) ? rabbitProps.getInventorySuccessRoutingKey() : rabbitProps.getInventoryFailedRoutingKey();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid event type: " + eventTypeName);
        }
    }
}