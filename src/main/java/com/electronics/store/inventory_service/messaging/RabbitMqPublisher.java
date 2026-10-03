package com.electronics.store.inventory_service.messaging;

import com.electronics.store.inventory_service.messaging.message.MessageEvent;
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
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class RabbitMqPublisher implements EventPublisher {

    private static final String TYPE_ID = "__TypeId__";

    private final RabbitTemplate rabbitTemplate;
    private final RabbitMqProperties rabbitMqProperties;


    @Override
    public void publish(UUID orderId, String payload) {
        try {
            log.info("Sending message for orderId: {}, {}", orderId, payload);
            final MessageProperties properties = MessagePropertiesBuilder.newInstance()
                    .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                    .setHeader(TYPE_ID, MessageEvent.class.getName())
                    .build();
            final Message message = new Message(payload.getBytes(StandardCharsets.UTF_8), properties);
            rabbitTemplate.send(rabbitMqProperties.getInventoryExchange(), rabbitMqProperties.getInventorySuccessRoutingKey(), message);
            log.info("Message sent for orderId: {}", orderId);
        } catch (AmqpException e) {
            log.error("Failed to send message to exchange={}, routingKey={}", rabbitMqProperties.getInventoryExchange(), rabbitMqProperties.getInventorySuccessRoutingKey(), e);
            throw e;
        }
    }
}
