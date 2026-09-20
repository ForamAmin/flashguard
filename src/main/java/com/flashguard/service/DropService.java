package com.flashguard.service;

import com.flashguard.entity.Drop;
import com.flashguard.entity.DropStatus;
import com.flashguard.entity.Organization;
import com.flashguard.repository.DropRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class DropService {

    private final DropRepository dropRepository;
    private final InventoryService inventoryService;

    public DropService(DropRepository dropRepository, InventoryService inventoryService) {
        this.dropRepository = dropRepository;
        this.inventoryService = inventoryService;
    }

    public Drop createDrop(Organization organization, String name, Integer totalInventory) {
        Drop drop = new Drop();
        drop.setOrganization(organization);
        drop.setName(name);
        drop.setTotalInventory(totalInventory);
        drop.setStatus(DropStatus.ACTIVE);
        Drop saved = dropRepository.save(drop);

        inventoryService.initializeInventory(saved.getId(), totalInventory);

        return saved;
    }

    // CHANGED: now takes the requesting Organization and enforces ownership
    public Drop getDrop(UUID dropId, Organization requestingOrg) {
        Drop drop = dropRepository.findById(dropId)
                .orElseThrow(() -> new DropNotFoundException("Drop not found: " + dropId));

        if (!drop.getOrganization().getId().equals(requestingOrg.getId())) {
            // Deliberately the same exception/message as "not found" —
            // don't reveal that a drop with this ID exists but belongs to someone else
            throw new DropNotFoundException("Drop not found: " + dropId);
        }

        return drop;
    }
}