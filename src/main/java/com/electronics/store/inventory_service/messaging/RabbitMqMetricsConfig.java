package com.electronics.store.inventory_service.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.boot.amqp.metrics.RabbitMetrics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;
import java.util.List;

@Configuration
public class RabbitMqMetricsConfig {

    @Bean
    public RabbitMetrics rabbitMetrics(CachingConnectionFactory connectionFactory, MeterRegistry meterRegistry) {
        List<Tag> tags = Collections.emptyList();
        com.rabbitmq.client.ConnectionFactory nativeConnectionFactory = connectionFactory.getRabbitConnectionFactory();
        RabbitMetrics rabbitMetrics = new RabbitMetrics(nativeConnectionFactory, tags);
        rabbitMetrics.bindTo(meterRegistry);
        return rabbitMetrics;
    }
}