package com.flashguard.repository;

import com.flashguard.entity.Claim;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface ClaimRepository extends JpaRepository<Claim, UUID> {
}