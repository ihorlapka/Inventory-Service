package com.electronics.store.inventory_service.messaging;

import com.electronics.store.outbox_event_publisher.rabbit.RabbitMqPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class RabbitMqConfig {

    private final RabbitMqProperties rabbitMqProperties;

    @Bean
    public Queue ordersQueue() {
        return QueueBuilder.durable(rabbitMqProperties.getOrdersQueueName())
                .quorum()
                .build();
    }

    @Bean
    public Queue inventoriesQueue() {
        return QueueBuilder.durable(rabbitMqProperties.getInventoriesQueueName())
                .quorum()
                .build();
    }

    @Bean
    public TopicExchange ordersExchange() {
        return new TopicExchange(rabbitMqProperties.getExchange());
    }

    @Bean
    public Binding ordersBinding() {
        return BindingBuilder.bind(ordersQueue())
                .to(ordersExchange())
                .with(rabbitMqProperties.getOrdersRoutingKey());
    }

    @Bean
    public Binding inventoriesBinding() {
        return BindingBuilder.bind(inventoriesQueue())
                .to(ordersExchange())
                .with(rabbitMqProperties.getInventoriesRoutingKey());
    }

    @Bean
    public RabbitMqPublisher rabbitMqPublisher(RabbitTemplate rabbitTemplate) {
        return new RabbitMqPublisher(rabbitTemplate, rabbitMqProperties.getExchange(),
                rabbitMqProperties.getInventoriesQueueName());
    }

    @Bean
    public MessageConverter messageConverter() {
        return new JacksonJsonMessageConverter();
    }
}
