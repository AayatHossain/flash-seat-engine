-- Token bucket shared by every service replica. Same rules as TokenBucket.java:
-- the bucket holds up to `capacity` tokens, refills steadily, and each request spends one.
-- Runs atomically, and uses Redis's own clock so replicas with slightly different clocks agree.
--
-- KEYS[1] = hash holding this caller's bucket (fields: tokens, updated)
-- ARGV[1] = capacity
-- ARGV[2] = tokens added per second
-- ARGV[3] = seconds to keep an idle bucket
-- Returns { 1 if allowed else 0, milliseconds until the next token when refused }

local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local capacity = tonumber(ARGV[1])
local refill_per_second = tonumber(ARGV[2])

local bucket = redis.call('HMGET', KEYS[1], 'tokens', 'updated')
local tokens = tonumber(bucket[1])
local updated = tonumber(bucket[2])
if tokens == nil then
    tokens = capacity
    updated = now
end

local elapsed = math.max(0, now - updated)
tokens = math.min(capacity, tokens + elapsed * refill_per_second / 1000)

local allowed = 0
local retry_after = 0
if tokens >= 1 then
    tokens = tokens - 1
    allowed = 1
else
    retry_after = math.ceil((1 - tokens) / refill_per_second * 1000)
end

redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'updated', now)
redis.call('EXPIRE', KEYS[1], ARGV[3])
return { allowed, retry_after }
