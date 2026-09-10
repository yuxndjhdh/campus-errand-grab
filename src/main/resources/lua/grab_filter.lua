-- Marker values are "deadlineMillis|publisherId". The known key distinguishes
-- a consumed marker from a marker lost during a Redis restart/FLUSHDB.
local marker = redis.call('GET', KEYS[1])
if not marker then
  if redis.call('GET', KEYS[2]) then return 0 end
  return -2
end
local separator = string.find(marker, '|')
local deadline = tonumber(separator and string.sub(marker, 1, separator - 1) or marker)
local publisher = tonumber(separator and string.sub(marker, separator + 1) or '0')
if publisher > 0 and publisher == tonumber(ARGV[2]) then return 2 end
if deadline < tonumber(ARGV[1]) then return -1 end
redis.call('DEL', KEYS[1])
return 1
