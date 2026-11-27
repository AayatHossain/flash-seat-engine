-- Deletes a lock only if we still own it. Without this check, a slow owner whose lock already
-- timed out could delete the lock that another request has taken since.
--
-- KEYS[1] = lock key
-- ARGV[1] = the random token we stored when taking the lock

if redis.call('GET', KEYS[1]) == ARGV[1] then
    return redis.call('DEL', KEYS[1])
end
return 0
