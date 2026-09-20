package com.flashguard.controller;

import com.flashguard.dto.CreateDropRequest;
import com.flashguard.dto.DropResponse;
import com.flashguard.entity.Drop;
import com.flashguard.entity.Organization;
import com.flashguard.service.DropNotFoundException;
import com.flashguard.service.DropService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/v1/drops")
public class DropController {

    private final DropService dropService;

    public DropController(DropService dropService) {
        this.dropService = dropService;
    }

    @PostMapping
    public ResponseEntity<DropResponse> create(@RequestBody CreateDropRequest request, HttpServletRequest httpRequest) {
        Organization org = (Organization) httpRequest.getAttribute("organization");
        Drop drop = dropService.createDrop(org, request.name(), request.totalInventory());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(drop));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable UUID id, HttpServletRequest httpRequest) {
        Organization org = (Organization) httpRequest.getAttribute("organization");

        try {
            Drop drop = dropService.getDrop(id, org);
            return ResponseEntity.ok(toResponse(drop));
        } catch (DropNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        }
    }

    private DropResponse toResponse(Drop drop) {
        return new DropResponse(
                drop.getId(),
                drop.getName(),
                drop.getTotalInventory(),
                drop.getStatus(),
                drop.getCreatedAt()
        );
    }
}