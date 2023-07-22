package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

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

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private  RedissonClient redissonClient;

    private IVoucherOrderService proxy;

    //加载秒杀订单校验资格lua脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    //创建订单阻塞队列
    private BlockingQueue<VoucherOrder> orderTasks =new ArrayBlockingQueue<>(1024*1024);

    //创建订单线程
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    private String queueName ="stream.order";

    //初始化创建订单线程,当类加载时就会执行
    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(()->{
            while (true){
                try {
                    //从消息队列中获取订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS streams.order >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    //判断消息是否获取成功
                    if (list.isEmpty()||list==null){
                        //获取失败，说明没有消息，进入下一次循环
                        continue;
                    }
                    //解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //如果获取成功，可以下单
                    handleVoucherOrder(voucherOrder);
                    //ACK确认 SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());


                } catch (Exception e) {
                    log.error("订单处理异常");
                    handlePendingList();
                }
            }
        });
    }

    private void handlePendingList() {
        while (true){
            try {
                //从消息队列中获取订单信息 XREADGROUP GROUP g1 c1 COUNT 1 STREAMS streams.order 0
                List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"),
                        StreamReadOptions.empty().count(1),
                        StreamOffset.create(queueName, ReadOffset.from("0"))
                );
                //判断消息是否获取成功
                if (list.isEmpty()||list==null){
                    //获取失败，说明pendingList没有异常消息，结束循环
                    break;
                }
                //解析消息中的订单信息
                MapRecord<String, Object, Object> record = list.get(0);
                Map<Object, Object> values = record.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                //如果获取成功，可以下单
                handleVoucherOrder(voucherOrder);
                //ACK确认 SACK stream.orders g1 id
                stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());


            } catch (Exception e) {
                log.error("pendingList订单处理异常");
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ex) {
                    throw new RuntimeException(ex);
                }
            }
        }
    }

//    //初始化创建订单线程,当类加载时就会执行
//    @PostConstruct
//    private void init(){
//        SECKILL_ORDER_EXECUTOR.submit(()->{
//            while (true){
//                try {
//                    //从阻塞队列中获取订单
//                    VoucherOrder voucherOrder = orderTasks.take();
//                    //创建订单
//                    handleVoucherOrder(voucherOrder);
//                } catch (InterruptedException e) {
//                    e.printStackTrace();
//                }
//            }
//        });
//    }


    //注入自己，解决事务失效问题
//    @Resource
//    private IVoucherOrderService voucherOrderService;

    //使用redis消息队列实现异步秒杀下单
    @Override
    public Result seckillVoucher(Long voucherId) throws InterruptedException {
        //获取用户
        Long userId = UserHolder.getUser().getId();
        // 6.1.订单id
        long orderId = redisIdWorker.nextId("order");
        //执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(),String.valueOf(orderId)
        );
        //判断结果是否为0
        int r =result.intValue();
        if (r != 0){
            //不为零，代表没有购买资格
            return Result.fail(r==1?"库存不足":"请勿重复下单！");
        }

        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //返回订单id
        return Result.ok(orderId);
    }

//    //使用阻塞队列实现异步秒杀下单
//    @Override
//    public Result seckillVoucher(Long voucherId) throws InterruptedException {
//        //获取用户
//        Long userId = UserHolder.getUser().getId();
//
//        //执行lua脚本
//        Long result = stringRedisTemplate.execute(
//                SECKILL_SCRIPT,
//                Collections.emptyList(),
//                voucherId.toString(), userId.toString()
//        );
//        //判断结果是否为0
//        int r =result.intValue();
//        if (r != 0){
//            //不为零，代表没有购买资格
//            return Result.fail(r==1?"库存不足":"请勿重复下单！");
//        }
//        // 为零，有购买资格，把下单信息保存到阻塞队列
//        //6.创建订单
//        VoucherOrder voucherOrder = new VoucherOrder();
//        // 6.1.订单id
//        long orderId = redisIdWorker.nextId("order");
//        voucherOrder.setId(orderId);
//        // 6.2.用户id
//        voucherOrder.setUserId(userId);
//        // 6.3.代金券id
//        voucherOrder.setVoucherId(voucherId);
//        //添加到阻塞队列
//        orderTasks.put(voucherOrder);
//        proxy = (IVoucherOrderService) AopContext.currentProxy();
//        //返回订单id
//        return Result.ok(orderId);
//    }




    private void handleVoucherOrder(VoucherOrder voucherOrder) throws InterruptedException {
        Long userId = voucherOrder.getUserId();
        //创建锁对象
//        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //获取锁
        boolean isLock = lock.tryLock(1200, TimeUnit.SECONDS);
        //判断是否获取锁成功
        if (!isLock) {
            //获取锁失败
            log.error("不允许重复下单！");
            return;
        }
        // 下单

        try {
            //获取代理对象
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            //释放锁
            lock.unlock();
        }
    }

//    @Override
//    public Result seckillVoucher(Long voucherId) throws InterruptedException {
//        // 1.查询优惠券
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        // 2.判断秒杀是否开始
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            // 尚未开始
//            return Result.fail("秒杀尚未开始！");
//        }
//        // 3.判断秒杀是否已经结束
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            // 尚未开始
//            return Result.fail("秒杀已经结束！");
//        }
//        // 4.判断库存是否充足
//        if (voucher.getStock() < 1) {
//            // 库存不足
//            return Result.fail("库存不足！");
//        }
//        Long userId = UserHolder.getUser().getId();
//        //创建锁对象
////        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//        RLock lock = redissonClient.getLock("order:" + userId);
//        //获取锁
//        boolean isLock = lock.tryLock(1200, TimeUnit.SECONDS);
//        //判断是否获取锁成功
//        if (!isLock) {
//            //获取锁失败
//            return Result.fail("不允许重复下单！");
//        }
//        // 下单
//
//        try {
//            //获取代理对象
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } finally {
//            //释放锁
//            lock.unlock();
//        }
//
//
////        synchronized (userId.toString().intern()) {
////            //获取使用AopContext获取代理对象解决事务失效问题
////            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
////            return proxy.createVoucherOrder(voucherId,userId);
////            //注入自己，解决事务失效问题，需要打开allow-circular-references: true
//////            return voucherOrderService.createVoucherOrder(voucherId,userId);
////        }
//
//    }



    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        // 5.一人一单
        Long userId = UserHolder.getUser().getId();
        // 5.1.查询订单
        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        // 5.2.判断是否已经下单
        if (count > 0) {
            // 已经下单
            return Result.fail("请勿重复购买！");
        }
        //5，扣减库存
//        boolean success = seckillVoucherService.update()
//                .setSql("stock= stock -1")
//                .eq("voucher_id", voucherId).update();
        //乐观锁
        boolean success = seckillVoucherService.update()
                .setSql("stock= stock -1") //set stock = stock -1
//                .eq("voucher_id", voucherId).eq("stock",voucher.getStock()).update(); //where id = ？ and stock = ?
                .eq("voucher_id", voucherId).gt("stock", 0).update(); //where id = ？ and stock = ?
        if (!success) {
            //扣减库存
            return Result.fail("库存不足！");
        }
        //6.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 6.1.订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 6.2.用户id
        voucherOrder.setUserId(userId);
        // 6.3.代金券id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);

        return Result.ok(orderId);

    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 5.一人一单
        Long userId = voucherOrder.getUserId();
        // 5.1.查询订单
        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        // 5.2.判断是否已经下单
        if (count > 0) {
            // 已经下单
            log.error("请勿重复购买！");
            return;
        }
        //5，扣减库存
//        boolean success = seckillVoucherService.update()
//                .setSql("stock= stock -1")
//                .eq("voucher_id", voucherId).update();
        //乐观锁
        boolean success = seckillVoucherService.update()
                .setSql("stock= stock -1") //set stock = stock -1
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0)
                .update(); //where id = ？ and stock = ?
        if (!success) {
            //扣减库存
            log.error("库存不足！");
            return ;
        }
        //6.创建订单
        save(voucherOrder);



    }
}
