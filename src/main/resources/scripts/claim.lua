local current = tonumber(redis.call('GET', KEYS[1]))

if current == nil then
    return -1
end

if current > 0 then
    redis.call('DECR', KEYS[1])
    return 1
else
    return 0
end