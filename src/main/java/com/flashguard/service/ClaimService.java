package com.flashguard.service;

import com.flashguard.entity.Claim;
import com.flashguard.entity.ClaimStatus;
import com.flashguard.entity.Drop;
import com.flashguard.repository.ClaimRepository;
import org.springframework.stereotype.Service;

@Service
public class ClaimService {

    private final ClaimRepository claimRepository;
    private final InventoryService inventoryService;

    public ClaimService(ClaimRepository claimRepository, InventoryService inventoryService) {
        this.claimRepository = claimRepository;
        this.inventoryService = inventoryService;
    }

    public Claim attemptClaim(Drop drop, String customerReference) {
        InventoryService.ClaimResult result = inventoryService.tryClaim(drop.getId());

        ClaimStatus status = switch (result) {
            case SUCCESS -> ClaimStatus.SUCCESS;
            case SOLD_OUT, INVENTORY_NOT_INITIALIZED -> ClaimStatus.SOLD_OUT;
        };

        Claim claim = new Claim();
        claim.setDrop(drop);
        claim.setCustomerReference(customerReference);
        claim.setStatus(status);

        return claimRepository.save(claim);
    }
}