package com.flashguard.repository;

import com.flashguard.entity.Organization;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {
    Optional<Organization> findByApiKeyHash(String apiKeyHash);
}