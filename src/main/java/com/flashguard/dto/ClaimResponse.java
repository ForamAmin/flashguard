package com.flashguard.dto;

import com.flashguard.entity.ClaimStatus;
import java.util.UUID;

public record ClaimResponse(ClaimStatus status, UUID claimId) {
}