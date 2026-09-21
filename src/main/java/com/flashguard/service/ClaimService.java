package com.flashguard.service;

import com.flashguard.entity.Claim;
import com.flashguard.entity.ClaimStatus;
import com.flashguard.entity.Drop;
import com.flashguard.repository.ClaimRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
public class ClaimService {

    private static final Logger log = LoggerFactory.getLogger(ClaimService.class);
    private static final int MAX_RETRIES = 3;

    private final ClaimRepository claimRepository;
    private final InventoryService inventoryService;

    public ClaimService(ClaimRepository claimRepository, InventoryService inventoryService) {
        this.claimRepository = claimRepository;
        this.inventoryService = inventoryService;
    }

    public Claim attemptClaim(Drop drop, String customerReference) {
        InventoryService.ClaimResult result = inventoryService.tryClaim(drop.getId());

        if (result == InventoryService.ClaimResult.INVENTORY_NOT_INITIALIZED) {
            inventoryService.initializeInventory(drop.getId(), drop.getTotalInventory());
            result = inventoryService.tryClaim(drop.getId());
        }

        ClaimStatus status = (result == InventoryService.ClaimResult.SUCCESS)
                ? ClaimStatus.SUCCESS
                : ClaimStatus.SOLD_OUT;

        return saveWithRetry(drop, customerReference, status);
    }

    private Claim saveWithRetry(Drop drop, String customerReference, ClaimStatus status) {
        Claim claim = new Claim();
        claim.setDrop(drop);
        claim.setCustomerReference(customerReference);
        claim.setStatus(status);

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                return claimRepository.save(claim);
            } catch (DataAccessException e) {
                log.warn("Postgres write failed for claim (drop={}, customer={}, status={}), attempt {}/{}",
                        drop.getId(), customerReference, status, attempt, MAX_RETRIES, e);

                if (attempt == MAX_RETRIES) {
                    // This is the serious case flagged in the write-up: Redis has already
                    // committed a SUCCESS decision, but we cannot persist the record of it.
                    log.error("CRITICAL: claim outcome '{}' for drop={} customer={} was decided by Redis "
                                    + "but could not be persisted to Postgres after {} attempts. "
                                    + "Inventory count is correct; audit record is MISSING.",
                            status, drop.getId(), customerReference, MAX_RETRIES);
                    throw new ClaimPersistenceException(
                            "Could not persist claim after " + MAX_RETRIES + " attempts", e);
                }

                try {
                    Thread.sleep(100L * attempt); // simple linear backoff
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new ClaimPersistenceException("Interrupted during claim retry", interrupted);
                }
            }
        }

        throw new IllegalStateException("Unreachable");
    }
}