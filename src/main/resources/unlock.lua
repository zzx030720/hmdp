--释放锁的lua脚本

-- KEYS[1] 是锁的key
-- ARGV[1] 是锁的标识
if (redis.call("get",KEYS[1]) == ARGV[1]) then
    return redis.call("del",KEYS[1])
end
return 0
