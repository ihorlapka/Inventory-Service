package com.electronics.store.inventory_service.messaging;

import com.electronics.store.inventory_service.messaging.message.OrderCreatedData;
import com.electronics.store.inventory_service.processor.ReservationProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RabbitMqConsumer {

    private final ReservationProcessor reservationProcessor;

    @RabbitListener(queues = "#{@rabbitMqProperties.queue()}")
    void onOrderCreated(OrderCreatedData event, @Header(value = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
        try {
            log.info("Received OrderCreatedData: {}", event);
            reservationProcessor.processOrderCreated(event);
        } catch (Exception e) {
            throw new AmqpRejectAndDontRequeueException("Invalid event " + event, e);
        }
    }
}
