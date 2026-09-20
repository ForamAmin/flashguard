package com.flashguard.dto;

import java.util.UUID;

public record OrganizationResponse(UUID id, String name, String apiKey) {
}