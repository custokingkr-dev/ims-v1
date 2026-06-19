package com.custoking.ims.commandcenter.dto;

import java.util.List;
import java.util.UUID;

public record DeliveryStatusResponse(
        UUID broadcastId,
        int total,
        int delivered,
        int failed,
        int pending,
        List<String> channels
) {}
