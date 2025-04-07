package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{

    private String name;//锁的名字
    private  StringRedisTemplate stringRedisTemplate;
    private  static final String KEY_PREFIX = "lock:";//锁的key前缀
    private  static final String ID_PREFIX = UUID.randomUUID().toString(true)+"-";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>();
    //DefaultRedisScript是hutool提供的一个类，用于执行lua脚本
    static {
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    // String

    @Override
    public boolean tryLock(long timeoutSec) {

        //获得线程id作为value
        String threadId = ID_PREFIX+Thread.currentThread().getId();

        // 获得锁
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);

        return BooleanUtil.isTrue(success);
    }

//    @Override
//    public void unLock() {
//        //获得当前线程标识
//        String threadId = ID_PREFIX+Thread.currentThread().getId();
//        //获得锁的标识(通过key来获得value，这个value就是当前线程标识)
//        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
//        //判断标识是否一致
//        if (threadId.equals(id)){
//            stringRedisTemplate.delete(KEY_PREFIX+name);
//        }
//        //释放锁
//        stringRedisTemplate.delete(KEY_PREFIX+name);
//    }

    //Lua版本,只在一行代码中执行，是满足原子性的
    @Override
    public void unLock() {
        //调用lua脚本
        //第一个参数UNLOCK_SCRIPT：是你提前写好的操作说明书（Lua脚本）。比如说明书上写着："检查这把锁是不是我自己的，如果是就打开，否则别碰它"。
        //(KEYS[1])第二个参数Collections.singletonList(...)：相当于你递给助手的钥匙串。虽然这里只有一把钥匙（比如lock:order123），但助手规定必须用钥匙串的形式传递，哪怕只有一把。
        //(ARGV[1])第三个参数ID_PREFIX+...：是你的身份证复印件（比如thread_25）。助手会拿着这个证件去核对锁上的签名，确保只有真正拥有锁的人才能开锁。
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX+name),//第二个参数是要接受一个集合，singletonList是返回一个不可变的集合
                ID_PREFIX+Thread.currentThread().getId()
        );
    }

}
