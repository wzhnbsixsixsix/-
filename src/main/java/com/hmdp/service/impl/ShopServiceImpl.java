package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    //线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    @Override
    public Result queryById(Long id) {
        //缓存穿透
        //id2->getByid(id2)也可以简写为this::getByid
        Shop shop =cacheClient.queryWithPassThrough(CACHE_SHOP_KEY , id, Shop.class, id2->getById(id2), CACHE_NULL_TTL, TimeUnit.MINUTES);

        //缓存击穿
//        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, id2 -> getById(id2), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

//封装到了CacheClinet工具类中
    //缓存穿透(数据库和redis都没有值的时候，给一个空对象)
    /*我们封装到了CacheClinet工具类中
    public Shop queryWithPassThrough(Long id) {

        // 1.从redis中查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get("cache:shop:" + id);
        // 2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {//hutool工具类，检查字符串不能为null或者空
            // 3.存在，直接返回
            //Hutool 的 JSONUtil:JSON 字符串反序列化为 Java 对象
            //用法: JSONUtil.toBean(JSON 字符串, 目标类);->返回一个目标类的Java对象
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        //再判断一次是否为空，如果为空就要报错一次
        if (shopJson != null) {
            return null;
        }


        // 4.不存在，根据id查询数据库
        //为什么可以直接用getById？
        //因为ServiceImpl<ShopMapper, Shop> 是 MyBatis-Plus 框架中的一个核心类，
        // 用于简化数据库操作。它通过泛型参数将 Mapper 接口 和 实体类 绑定
        //就是说继承了ServiceImpl<ShopMapper, Shop> 后，就可以直接使用 getById()、save()、updateById() 等方法，
        //ServiceImpl<Mapper接口,实体类 >
        //Mapper接口里面含有一系列的自定义的方法
        //实体类就是它包含了数据库中的表所对应的字段和方法，也就是告诉这个东西的结构

        Shop shop = getById(id);

        // 5.数据库不存在，将空值写入redis(防止缓存穿透)
        if (shop == null) {
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY, "", CACHE_SHOP_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 6.存在，写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 7.返回
        return shop;
    }
    */

    //互斥锁防止缓存击穿(缓存中热点突然消失，然后压爆数据库)
    /*public Shop queryWithMutex(Long id) {

        // 1.从redis中查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get("cache:shop:" + id);
        // 2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {//hutool工具类，检查字符串不能为null或者空
            // 3.存在，直接返回
            //Hutool 的 JSONUtil:JSON 字符串反序列化为 Java 对象
            //用法: JSONUtil.toBean(JSON 字符串, 目标类);->返回一个目标类的Java对象
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        //再判断一次是否为空，如果为空就要报错一次
        if (shopJson != null) {//我们要处理有值，空值，和null的情况，上面的if处理了有值的情况，这里处理空值的情况
            return null;
        }

        //4.实现缓存重建
        //4.1 获取互斥锁
        String lockKey = "lock:shop:" + id;//通过SETNX来实现锁的功能，这个id作为键，值代表这个锁是否被获得
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);//有没有获取成功？
            //4.2 判断是否获取锁成功
            if (!isLock) {
                //4.3 如果失败，则休眠并且重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            // 4.4 如果成功则查询数据库
            //为什么可以直接用getById？
            //因为ServiceImpl<ShopMapper, Shop> 是 MyBatis-Plus 框架中的一个核心类，
            // 用于简化数据库操作。它通过泛型参数将 Mapper 接口 和 实体类 绑定
            //就是说继承了ServiceImpl<ShopMapper, Shop> 后，就可以直接使用 getById()、save()、updateById() 等方法，
            //ServiceImpl<Mapper接口,实体类 >
            //Mapper接口里面含有一系列的自定义的方法
            //实体类就是它包含了数据库中的表所对应的字段和方法，也就是告诉这个东西的结构

            shop = getById(id);

            // 5.数据库不存在，将空值写入redis(防止缓存穿透)
            if (shop == null) {
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY, "", CACHE_SHOP_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 6.存在，写入redis
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //7.释放互斥锁
            unLock(lockKey);
        }

        // 8.返回
        return shop;
    }*/



    //逻辑过期解决缓存击穿
   /* public Shop queryWithLogicalExpire(Long id) {

        // 1.从redis中查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get("cache:shop:" + id);
        // 2.判断是否存在
        if (StrUtil.isBlank(shopJson)) {//hutool工具类
            // 3.不存在
            return null;
        }

        //4需要先把JSON反序列化为java对象
        //shopjson->Redisdata->Redisdata(json版本)->Shop
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();//需要把RedisData对象转为JSONObject
        Shop shop = JSONUtil.toBean(data, Shop.class);//再把这个JSON转为Shop类型
        LocalDateTime expireTime = redisData.getExpireTime();
        //5.如果缓存中存在数据，则需要判断过期时间
        if (expireTime.isAfter(LocalDateTime.now())) {//expireTime就是LocalDataTime.now()+一个过期时间间隔，比如15.30过期，现在是15.25，说明没过期
            //如果未过期，直接返回店铺信息
            return shop;
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
                    this.saveShop2Redis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unLock(lockKey);
                }

            });

        }

        //6.4 如果失败，则返回过期的商铺信息

        // 7.返回
        return shop;
    }*/


    // 尝试获取互斥锁
 /*   private boolean tryLock(String key) {
        //利用setnx(setIfAbsent)来实现互斥锁
        // setnx:当且仅当没有值的时候才能设置值
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);

        //return flag;//为什么不能直接返回flag？flag是Boolean类型，会先进行拆箱变成boolean类型，这个时候flag的值可能为null，出现空指针
        return BooleanUtil.isTrue(flag);//用hutool工具类来判断，如果是null就会返回false
    }*/

    //释放锁
   /* private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }*/


    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        //1.更新数据库
        updateById(shop);
        //2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }

//测试
//    public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
//        //1.查询店铺数据
//        Shop shop = getById(id);
//        Thread.sleep(200);
//        //2.封装逻辑过期时间
//        RedisData redisData = new RedisData();
//        redisData.setData(shop);
//        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
//        //3.将数据写入redis
//        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData), expireSeconds, TimeUnit.SECONDS);
//    }


}