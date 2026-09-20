package com.flashguard.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class InventoryService {

    private final StringRedisTemplate redisTemplate;

    public InventoryService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public String pingRedis() {
        return redisTemplate.getConnectionFactory()
                .getConnection()
                .ping();
    }

    // NEW: initializes the live inventory counter for a newly created drop
    public void initializeInventory(UUID dropId, int totalInventory) {
        redisTemplate.opsForValue().set(inventoryKey(dropId), String.valueOf(totalInventory));
    }

    // NEW: key-naming convention, centralized here since the claim engine will need the exact same key later
    public static String inventoryKey(UUID dropId) {
        return "drop:" + dropId + ":inventory";
    }
}