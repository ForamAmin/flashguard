package com.flashguard.service;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.UUID;

@Service
public class InventoryService {

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> claimScript;

    public InventoryService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;

        this.claimScript = new DefaultRedisScript<>();
        claimScript.setLocation(new ClassPathResource("scripts/claim.lua"));
        claimScript.setResultType(Long.class);
    }

    public String pingRedis() {
        return redisTemplate.getConnectionFactory().getConnection().ping();
    }

    public void initializeInventory(UUID dropId, int totalInventory) {
        redisTemplate.opsForValue().set(inventoryKey(dropId), String.valueOf(totalInventory));
    }

    // NEW: runs the Lua script atomically against Redis
    public ClaimResult tryClaim(UUID dropId) {
        Long result = redisTemplate.execute(
                claimScript,
                Collections.singletonList(inventoryKey(dropId))
        );

        if (result == null || result == -1L) {
            return ClaimResult.INVENTORY_NOT_INITIALIZED;
        } else if (result == 1L) {
            return ClaimResult.SUCCESS;
        } else {
            return ClaimResult.SOLD_OUT;
        }
    }

    public static String inventoryKey(UUID dropId) {
        return "drop:" + dropId + ":inventory";
    }

    public enum ClaimResult {
        SUCCESS, SOLD_OUT, INVENTORY_NOT_INITIALIZED
    }
    public int getCurrentInventory(UUID dropId) {
        String value = redisTemplate.opsForValue().get(inventoryKey(dropId));
        return value != null ? Integer.parseInt(value) : 0;
    }
}