package com.flashguard.controller;

import com.flashguard.dto.CreateOrganizationRequest;
import com.flashguard.dto.OrganizationResponse;
import com.flashguard.entity.Organization;
import com.flashguard.repository.OrganizationRepository;
import com.flashguard.service.ApiKeyService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/organizations")
public class OrganizationController {

    private final OrganizationRepository organizationRepository;
    private final ApiKeyService apiKeyService;

    public OrganizationController(OrganizationRepository organizationRepository, ApiKeyService apiKeyService) {
        this.organizationRepository = organizationRepository;
        this.apiKeyService = apiKeyService;
    }

    @PostMapping
    public ResponseEntity<OrganizationResponse> create(@RequestBody CreateOrganizationRequest request) {
        String rawKey = apiKeyService.generateRawKey();
        String hashedKey = apiKeyService.hash(rawKey);

        Organization org = new Organization();
        org.setName(request.name());
        org.setApiKeyHash(hashedKey);
        organizationRepository.save(org);

        return ResponseEntity.ok(new OrganizationResponse(org.getId(), org.getName(), rawKey));
    }
}