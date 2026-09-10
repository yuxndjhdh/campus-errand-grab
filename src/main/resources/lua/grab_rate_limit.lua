local userCount = redis.call('INCR', KEYS[1])
if userCount == 1 then redis.call('EXPIRE', KEYS[1], ARGV[3]) end
local orderCount = redis.call('INCR', KEYS[2])
if orderCount == 1 then redis.call('EXPIRE', KEYS[2], ARGV[3]) end
if userCount > tonumber(ARGV[1]) or orderCount > tonumber(ARGV[2]) then return 0 end
return 1
