---
--- Generated by EmmyLua(https://github.com/EmmyLua)
--- Created by Thomas.
--- DateTime: 2025/4/2 19:08
---比较线程标识和锁中的标识的不同
if(redis.call("get",KEYS[1])==ARGV[1]) then
    --释放锁
    return redis.call("del",KEYS[1])
end
return 0