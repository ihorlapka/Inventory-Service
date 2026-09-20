package com.electronics.store.inventory_service.messaging;

import com.electronics.store.inventory_service.messaging.message.MessageEventIn;
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

    @RabbitListener(id = "orders-created",
            queues = "#{@rabbitMqProperties.getQueueName()}",
            concurrency = "${app.rabbit.orders.concurrency:2-8}",
            ackMode = "AUTO")
    public void handleMessage(MessageEventIn event,
                              @Header(value = AmqpHeaders.MESSAGE_ID) String messageId,
                              @Header(value = AmqpHeaders.CORRELATION_ID) String correlationId,
                              @Header(value = AmqpHeaders.TIMESTAMP) long sentTimestamp) {
        try {
            log.info("Received OrderCreatedData: {}, msgId: {}, correlationId: {}, sentTime: {}", event, messageId, correlationId, sentTimestamp);
            reservationProcessor.processOrderCreated(event);
        } catch (NullPointerException | IllegalArgumentException | IllegalStateException | ArrayIndexOutOfBoundsException e) {
            throw new AmqpRejectAndDontRequeueException("Invalid event " + event, e); // straight to Dead Letter Queue
        }
    }
}
