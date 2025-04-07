package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Component
@Slf4j
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    //存入redis
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    //逻辑过期版存入redis
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));//把时间转换为秒
        //放到redis中
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }


    //空对象解决缓存穿透(数据库和redis都没有值的时候，给一个空对象)
    //keyPefix:key的前缀
    //用泛型来控制返回类型
    //Function<T, R> 表示一个输入类型为 T，输出类型为 R 的函数。(在这里作为参数接受一个函数，我们可以让ID为输入，R为输出)
    //<R,ID>是一个占位符，表示一些不确定类型的函数，声明这个方法是一个泛型方法，放在返回值前面
    public <R, ID> R queryWithPassThrough(String keyPefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPefix + id;
        // 1.从redis中查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get("cache:shop:" + id);
        // 2.判断是否存在
        if (StrUtil.isNotBlank(json)) {//hutool工具类，检查字符串不能为null或者空
            // 3.存在，直接返回
            //Hutool 的 JSONUtil:JSON 字符串反序列化为 Java 对象
            //用法: JSONUtil.toBean(JSON 字符串, 目标类);->返回一个目标类的Java对象
            return JSONUtil.toBean(json, type);
        }

        //再判断一次是否为空，如果为空就要报错一次
        if (json != null) {
            return null;
        }


        // 4.不存在，根据id查询数据库
        R r = dbFallback.apply(id);//apply()：执行函数，返回计算结果。
        // 5.数据库不存在，将空值写入redis(防止缓存穿透)
        if (r == null) {
            set(key, "", time, unit);
            return null;
        }
        // 6.存在，写入redis
        set(key, r, time, unit);
        // 7.返回
        return r;
    }


    //线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    //逻辑过期解决缓存击穿
    public <R, ID> R queryWithLogicalExpire(String keyPefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {

        String key = keyPefix + id;
        // 1.从redis中查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isBlank(json)) {//hutool工具类
            // 3.不存在
            return null;
        }

        //4需要先把JSON反序列化为java对象
        //shopjson->Redisdata->Redisdata(json版本)->Shop
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();//需要把RedisData对象转为JSONObject
        R r = JSONUtil.toBean(data, type);//再把这个JSON转为Shop类型
        LocalDateTime expireTime = redisData.getExpireTime();

        //5.如果缓存中存在数据，则需要判断过期时间
        if (expireTime.isAfter(LocalDateTime.now())) {//expireTime就是LocalDataTime.now()+一个过期时间间隔，比如15.30过期，现在是15.25，说明没过期
            //如果未过期，直接返回店铺信息
            return r;
        }

        //6.如果成功则要缓存重建
        //6.1获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);//尝试获取锁
        //6.2 判断是否获取锁成功
        if (isLock) {
            //6.3 如果成功，则开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //查询数据库
                    R r1 = dbFallback.apply(id);
                    //写入redis
                    this.setWithLogicalExpire(key, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unLock(lockKey);
                }

            });

        }

        // 7.返回
        return r;
    }


    // 尝试获取互斥锁
    private boolean tryLock(String key) {
        //利用setnx(setIfAbsent)来实现互斥锁
        // setnx:当且仅当没有值的时候才能设置值
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);

        //return flag;//为什么不能直接返回flag？flag是Boolean类型，会先进行拆箱变成boolean类型，这个时候flag的值可能为null，出现空指针
        return BooleanUtil.isTrue(flag);//用hutool工具类来判断，如果是null就会返回false
    }

    //释放锁
    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }


}
