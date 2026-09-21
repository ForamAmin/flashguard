package com.flashguard.controller;

import com.flashguard.dto.ClaimResponse;
import com.flashguard.dto.CreateClaimRequest;
import com.flashguard.entity.Claim;
import com.flashguard.entity.Drop;
import com.flashguard.entity.Organization;
import com.flashguard.service.ClaimPersistenceException;
import com.flashguard.service.ClaimService;
import com.flashguard.service.DropNotFoundException;
import com.flashguard.service.DropService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/v1/drops/{dropId}/claims")
public class ClaimController {

    private final ClaimService claimService;
    private final DropService dropService;

    public ClaimController(ClaimService claimService, DropService dropService) {
        this.claimService = claimService;
        this.dropService = dropService;
    }

    @PostMapping
    public ResponseEntity<?> claim(
            @PathVariable UUID dropId,
            @RequestBody CreateClaimRequest request,
            HttpServletRequest httpRequest
    ) {
        Organization org = (Organization) httpRequest.getAttribute("organization");

        try {
            Drop drop = dropService.getDrop(dropId, org);
            Claim claim = claimService.attemptClaim(drop, request.customerReference());
            return ResponseEntity.ok(new ClaimResponse(claim.getStatus(), claim.getId()));
        } catch (DropNotFoundException e) {
            return ResponseEntity.status(404).body(e.getMessage());
        } catch (ClaimPersistenceException e) {
            return ResponseEntity.status(500).body("Claim outcome could not be confirmed. Please retry or contact support.");
        }


    }
}