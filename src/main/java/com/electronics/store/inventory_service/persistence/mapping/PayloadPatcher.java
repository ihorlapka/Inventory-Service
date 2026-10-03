package com.electronics.store.inventory_service.persistence.mapping;

import com.electronics.store.inventory_service.messaging.message.MessageEvent;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@UtilityClass
public class PayloadPatcher {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public String serialize(MessageEvent event) {
        try {
            return OBJECT_MAPPER.writeValueAsString(event);
        } catch (Exception e) {
            log.error("Error serializing event to JSON", e);
            throw new RuntimeException(e);
        }
    }

}
