package com.custoking.ims.operationsservice.api.internal;

import com.custoking.ims.operationsservice.outbox.OutboxRelay;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Request-driven relay trigger for a dedicated OIDC-authenticated Cloud Scheduler identity. */
@RestController
@RequestMapping("/api/v1/internal/outbox")
public class OutboxRelayTriggerController {

    private final OutboxRelay relay;

    public OutboxRelayTriggerController(OutboxRelay relay) {
        this.relay = relay;
    }

    @PostMapping("/relay")
    public Map<String, Integer> relay() {
        return Map.of("published", relay.publishBatch());
    }
}
