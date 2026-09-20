package com.flashguard.controller;

import com.flashguard.service.InventoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    private final InventoryService inventoryService;

    public HealthController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @GetMapping("/health/redis")
    public String redisHealth() {
        return inventoryService.pingRedis();
    }
}