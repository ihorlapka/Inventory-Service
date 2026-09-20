package com.electronics.store.inventory_service.messaging;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class RabbitMqPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final RabbitMqProperties rabbitMqProperties;


    public void publish(UUID orderId, String payload) {
        try {
            log.info("Sending message for orderId: {}, {}", orderId, payload);
            rabbitTemplate.convertAndSend(rabbitMqProperties.getExchange(), rabbitMqProperties.getRoutingKey(), payload);
            log.info("Message sent for orderId: {}", orderId);
        } catch (AmqpException e) {
            log.error("Failed to send message to exchange={}, routingKey={}, queue={}",
                    rabbitMqProperties.getExchange(), rabbitMqProperties.getRoutingKey(), rabbitMqProperties.getQueueName(), e);
            throw e;
        }
    }
}
