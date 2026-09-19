package com.electronics.store.inventory_service.messaging;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Getter
@Setter
@ToString
@Configuration
@ConfigurationProperties(RabbitMqProperties.PROPERTIES_PREFIX)
@RequiredArgsConstructor
public class RabbitMqProperties {

    final static String PROPERTIES_PREFIX = "rabbit";

    @Value("${" + PROPERTIES_PREFIX + ".queue.name}")
    private String queueName;

    @Value("${" + PROPERTIES_PREFIX + ".exchange.name}")
    private String exchange;

    @Value("${" + PROPERTIES_PREFIX + ".routing.key}")
    private String routingKey;
}
