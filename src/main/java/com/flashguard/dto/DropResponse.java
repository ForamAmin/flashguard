package com.flashguard.dto;

import com.flashguard.entity.DropStatus;
import java.time.Instant;
import java.util.UUID;

public record DropResponse(
        UUID id,
        String name,
        Integer totalInventory,
        DropStatus status,
        Instant createdAt
) {
}