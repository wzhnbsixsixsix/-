--作用：1.判断用户是否有购买资格2.发送到消息队列


-- 1.1优惠卷Id
local voucherId = ARGV［1］--ARGV[]数据可以从外面传进来
-- 1.2.用户Id
local userId = ARGV［2］
-- 1.3.订单Id
local orderId= ARGV[3]

-- 2. 数据key
-- 2.1. 库存key
local stockKey = 'seckill:stock:'..voucherId--拼接用 ..
-- 2.2.订单key
local orderKey = 'seckill:order:'..voucherId

-- 3.脚本业务

--3.1.判断库存是否充足 get stockKey
if(tonumber(redis.call('get',stockKey)) <= 0) then--redis存进去的是字符串，所以要转成数字
    -- 3.2.库存不足，返回1
    return 1
end

-- 3.2.判断用户是否下单SISMEMBER orderKey userId(我们用set来存用户Id,判断库存是否充足)
if(redis.call('sismember',orderKey,userId)== 1) then

    -- 3.3. 存在，说明是重复下单，返回2
    return 2

end
--3.4扣库存 incrby stockKey -1 (加-1就是-1)
redis.call('incrby',stockKey,-1)--就是说把键为stockKey的值-1

--3.5下单 保存用户 sadd orderKey userId
redis.call('sadd',orderKey,userId)

--3.6发送消息到队列中(下一步就是到数据库中进行库存扣除，实现异步下单),XADD stream.orders * k1 v1
redis.call('xadd','stream.orders','*','userId',userId,'voucherId',voucherId,'id',orderId)--我们已经在redis中提前创建了stream.orders