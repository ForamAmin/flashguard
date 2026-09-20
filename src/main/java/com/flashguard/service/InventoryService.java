package com.flashguard.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

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
}