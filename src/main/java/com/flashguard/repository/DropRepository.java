package com.flashguard.repository;

import com.flashguard.entity.Drop;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface DropRepository extends JpaRepository<Drop, UUID> {
}