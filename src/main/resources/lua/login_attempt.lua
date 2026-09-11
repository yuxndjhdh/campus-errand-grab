local accountCount = tonumber(redis.call('GET', KEYS[1]) or '0')
local ipCount = tonumber(redis.call('GET', KEYS[2]) or '0')

if ARGV[4] == '0' then
    if accountCount >= tonumber(ARGV[1]) or ipCount >= tonumber(ARGV[2]) then return 0 end
    return 1
end

accountCount = redis.call('INCR', KEYS[1])
if accountCount == 1 then redis.call('EXPIRE', KEYS[1], ARGV[3]) end
ipCount = redis.call('INCR', KEYS[2])
if ipCount == 1 then redis.call('EXPIRE', KEYS[2], ARGV[3]) end
return 1
