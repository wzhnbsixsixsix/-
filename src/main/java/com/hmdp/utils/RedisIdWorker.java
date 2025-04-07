package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;


//基于redis的全局ID生成器，调用nextId就可以生成
@Component
public class RedisIdWorker {
    private static final long BEGIN_TIMESTAMP = 1640995200L;

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    public long nextId(String keyPrefix) {//这个keyPrefix就是业务的前缀，因为有很多不用业务都需要一个id作为key
        //1.生成时间戳
        //思路：我们先设置了一个2022-01-01 00:00:00作为初始时间
        //然后我们再计算一下现在到1970-01-01 00:00:00 UTC的时间
        //再计算一下现在到UTC的时间减去初始时间，就是现在到初始时间过了多少秒了
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        //2.生成序列号
        //2.1 获取当前日期，精确到天
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        //2.2自增长
        //increment：对指定的 键 (key) 的值执行自增，如果该 key 不存在，则会初始化为 0，然后执行 +1 操作。
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);


        //3.拼接并且返回
        //进行位运算，左移timestamp32位，或运算就是+上county
        return timestamp << 32 | count;
    }

//    public static void main(String[] args) {
//    //2022-01-01 00:00:00 UTC 距离 1970-01-01 00:00:00 UTC 有 1640995200 秒。
//        LocalDateTime time =LocalDateTime.of(2022, 1, 1, 0, 0, 0);
//        long l = time.toEpochSecond(ZoneOffset.UTC);
//        System.out.println(l);//1640995200
//    }

}
