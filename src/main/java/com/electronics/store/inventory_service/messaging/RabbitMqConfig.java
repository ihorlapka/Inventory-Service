package com.electronics.store.inventory_service.messaging;

import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class RabbitMqConfig {

    private final RabbitMqProperties rabbitProps;

    //publish
    @Bean
    public TopicExchange inventoryEventsExchange() {
        return new TopicExchange(rabbitProps.getInventoryExchange());
    }

    //listen
    @Bean
    public Queue ordersQueue() {
        return QueueBuilder.durable(rabbitProps.getOrdersQueueName())
                .quorum()
                .build();
    }

    @Bean
    public TopicExchange ordersExchange() {
        return new TopicExchange(rabbitProps.getOrdersExchange());
    }

    @Bean
    public Binding ordersBinding(Queue ordersQueue, TopicExchange ordersExchange) {
        return BindingBuilder.bind(ordersQueue)
                .to(ordersExchange)
                .with(rabbitProps.getOrdersRoutingKey());
    }

    @Bean
    public MessageConverter messageConverter() {
        return new JacksonJsonMessageConverter();
    }
}
