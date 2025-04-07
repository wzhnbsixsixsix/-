package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    //为什么不用IVoucherService，因为我们要操作的是秒杀卷，而不是普通卷，我们把秒杀卷和秒杀卷的实体绑定在一起了
    @Resource
    private ISeckillVoucherService seckillVoucherService;//我们可以用seckillVoucherService来操作秒杀卷(CRUD)
    @Autowired
    private RedisIdWorker redisIdWorker;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;




    private static final DefaultRedisScript<Long> SECKILL_SCRIPT = new DefaultRedisScript<>();
    //DefaultRedisScript是hutool提供的一个类，用于执行lua脚本
    static {
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }


    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();//Executors.newSingleThreadExecutor() 表示这个线程池 永远只有一个线程在运行任务。

    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());//一开始就进行异步把数据存入数据库
    }

    private class VoucherOrderHandler implements Runnable {

        @Override
        public void run() {
            while (true) {
                try {
                    // 1.获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create("stream.orders", ReadOffset.lastConsumed())
                    );
                    // 2.判断订单信息是否为空
                    if (list == null || list.isEmpty()) {
                        // 如果为null，说明没有消息，继续下一次循环
                        continue;
                    }
                    // 解析数据
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 3.创建订单
                    createVoucherOrder(voucherOrder);
                    // 4.确认消息 XACK
                    stringRedisTemplate.opsForStream().acknowledge("s1", "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (true) {
                try {
                    // 1.获取pending-list中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create("stream.orders", ReadOffset.from("0"))
                    );
                    // 2.判断订单信息是否为空
                    if (list == null || list.isEmpty()) {
                        // 如果为null，说明没有异常消息，结束循环
                        break;
                    }
                    // 解析数据
                    MapRecord<String, Object, Object> record = list.get(0);
                    //getValue()获取value
                    Map<Object, Object> value = record.getValue();
                    //用hutool 的BeanUtil来把map转成VoucherOrder类，第三个参数是是否忽略字段不匹配或类型转换失败，设为 true 更安全
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 3.创建订单
                    createVoucherOrder(voucherOrder);
                    // 4.确认消息 XACK
                    stringRedisTemplate.opsForStream().acknowledge("s1", "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        //获得订单Id
        long orderid= redisIdWorker.nextId("order");//调用全局ID生成器

        //获取用户id
        Long userId = UserHolder.getUser().getId();
        //1.执行Lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),//我们这里没有key，就放了两个值在ARGV[]数组中
                voucherId.toString(), userId.toString(),String.valueOf(orderid)//long转string
        );

        //2.判断是否为0
        int r = result.intValue();
        if (r != 0) {
            //2.1.不为0：代表没有购买资格
            return Result.fail(r==1 ?  "库存不足":"没有购买资格");
        }


        //3.返回订单ID
        return Result.ok(0);

    }




//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        //获取用户id
//        Long userId = UserHolder.getUser().getId();
//        //1.执行Lua脚本
//        Long result = stringRedisTemplate.execute(
//                SECKILL_SCRIPT,
//                Collections.emptyList(),//我们这里没有key，就放了两个值在ARGV[]数组中
//                voucherId.toString(), userId.toString()
//        );
//        //2.判断是否为0
//        int r = result.intValue();
//        if (r != 0) {
//            //2.1.不为0：代表没有购买资格
//            return Result.fail(r==1 ?  "库存不足":"没有购买资格");
//        }
//
//        //2.2 为0:代表有购买资格,把订单储存到阻塞队列
//        long orderid= redisIdWorker.nextId("order");//调用全局ID生成器
//
//
//        //3.返回订单ID
//
//        return Result.ok(0);
//
//    }

    //用锁的方式执行实现一人一单，上面的新版本是Lua脚本实现一人一单
//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        //1.查询优惠价
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        //2.判断秒杀是否开始
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            //尚未开始
//            return Result.fail("秒杀尚未开始");
//        }
//        //3.判断秒杀是否结束
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            //已经结束
//            return Result.fail("秒杀已经结束");
//        }
//        //4.判断库存是否充足
//        if (voucher.getStock() < 1) {
//            //库存不足
//            return Result.fail("库存不足");
//        }
//
//
//        //5.判断用户是否已经购买过
//        //Long对比long可以支持null值
//        Long userId = UserHolder.getUser().getId();//之前实现了一个拦截器LoginInterceptor,我们把用户信息存储到了ThreadLocal(UserHolder是其实体类)中，所以这里就可以直接获取到
//
//
//        //创建锁对象

    /// /        SimpleRedisLock lock = new SimpleRedisLock("order" + userId, stringRedisTemplate);
//        RLock lock = redissonClient.getLock("order" + userId);
//
//        //获取锁
//        if (!lock.tryLock()) {
//            //获取锁失败，直接返回失败或者重试
//            return Result.fail("不允许重复下单");
//        }
//
//        //synchronized (userId.toString().intern()) {//对Id加悲观锁，只让一个用户进来判断
//        //userId 是一个 Long 类型，在 .toString() 之后，它变成了一个 String 对象。
//        //intern() 方法的作用是：确保字符串对象在 JVM 的字符串常量池（String Pool）中具有唯一性。
//        //具体来说：
//        //userId.toString() 本身会生成一个新的 String 对象（除非已经存在于常量池）。
//        //intern() 方法会将该 String 放入字符串常量池，并返回常量池中唯一的实例。
//        //这样，相同 userId 的线程就能锁住同一个 String 对象，实现全局唯一的同步锁。
//
//
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();//获得当前对象(VoucherOrderServiceImpl的对象)的代理对象
//            //通过代理对象来调用createVoucherOrder()
//            //否则的话seckillVoucher()直接调用createVoucherOrder()，没有经过代理层，事务会失效(事务是AOP创建的)
//
//
//            return proxy.createVoucherOrder(voucherId);//为什么要提取方法？因为我们希望提交事务后再释放锁，而不是提交事务前，这样会导致数据还没到数据库，锁就已经释放了
//            //事务：管理数据层面的原子性
//            //锁：解决线程并发
//        } catch (IllegalStateException e) {
//            throw new RuntimeException(e);
//        } finally {
//            //释放锁
//            lock.unlock();
//        }
//
//    }

    private void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        // 创建锁对象
        RLock redisLock = redissonClient.getLock("lock:order:" + userId);
        // 尝试获取锁
        boolean isLock = redisLock.tryLock();
        // 判断
        if (!isLock) {
            // 获取锁失败，直接返回失败或者重试
            log.error("不允许重复下单！");
            return;
        }

        try {
            // 5.1.查询订单
            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            // 5.2.判断是否存在
            if (count > 0) {
                // 用户已经购买过了
                log.error("不允许重复下单！");
                return;
            }

            // 6.扣减库存
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - 1") // set stock = stock - 1
                    .eq("voucher_id", voucherId).gt("stock", 0) // where id = ? and stock > 0
                    .update();
            if (!success) {
                // 扣减失败
                log.error("库存不足！");
                return;
            }

            // 7.创建订单
            save(voucherOrder);
        } finally {
            // 释放锁
            redisLock.unlock();
        }
    }
}
