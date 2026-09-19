package com.electronics.store.inventory_service.processor;

import com.electronics.store.inventory_service.messaging.message.OrderCreatedData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationProcessor {

    public void processOrderCreated(OrderCreatedData event) {

    }
}
