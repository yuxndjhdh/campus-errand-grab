local marker = redis.call('GET', KEYS[1])
if marker then return 0 end
redis.call('SET', KEYS[1], ARGV[1] .. '|' .. ARGV[3], 'EX', ARGV[2])
redis.call('SET', KEYS[2], '1', 'EX', ARGV[2])
return 1
