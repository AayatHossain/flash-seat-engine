-- Puts a buyer in an event's queue and returns their position.
-- Runs atomically inside Redis, so thousands of simultaneous joins get unique positions with no gaps,
-- and a buyer who joins twice keeps their first position.
--
-- KEYS[1] = hash of userId -> position
-- KEYS[2] = counter of the last position handed out
-- ARGV[1] = userId
-- ARGV[2] = seconds to keep the queue data

local existing = redis.call('HGET', KEYS[1], ARGV[1])
if existing then
    return tonumber(existing)
end

local position = redis.call('INCR', KEYS[2])
redis.call('HSET', KEYS[1], ARGV[1], position)
redis.call('EXPIRE', KEYS[1], ARGV[2])
redis.call('EXPIRE', KEYS[2], ARGV[2])
return position
