package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;
    private static final DefaultRedisScript<Long> SECkILL;
    static {
        SECkILL = new DefaultRedisScript<>();
        SECkILL.setLocation(new ClassPathResource("seckill.lua"));
        SECkILL.setResultType(Long.class);
    }
    private BlockingQueue<VoucherOrder> orderTsaks = new ArrayBlockingQueue<>(1024*1024);
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    private class VoucherOrderHandler implements Runnable{
        @Override
        public void run() {
            while (true){
                try {
                    VoucherOrder take = orderTsaks.take();
                    handleVoucherOrder(take);
                } catch (Exception e) {
                    log.error("处理订单异常",e);
                }
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //1.获取用户
        Long userId = voucherOrder.getUserId();
        // 2.创建锁对象
        RLock redisLock = redissonClient.getLock("lock:order:" + userId);
        // 3.尝试获取锁
        boolean isLock = redisLock.tryLock();
        // 4.判断是否获得锁成功
        if (!isLock) {
            // 获取锁失败，直接返回失败或者重试
            log.error("不允许重复下单！");
            return;
        }
        try {
            //注意：由于是spring的事务是放在threadLocal中，此时的是多线程，事务会失效
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            // 释放锁
            redisLock.unlock();
        }
    }

    private IVoucherOrderService proxy;
    @Override
    public Result seckillVoucher(Long voucherId) {
        //获取用户
        Long userid = UserHolder.getUser().getId();
        //1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECkILL,
                Collections.emptyList(),
                voucherId.toString(),
                userid.toString()
        );
        //2.判断结果是否为0
        // 先判断非空，再比较值
        if (result != null && result != 0) {
            // 2.1不会0，代表没有购买资格
            return Result.fail(result == 1 ? "库存不足" : "不能重复下单");
        }
        //2.2 为0，有购买资格，把信息放入阻塞队列
        //创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userid);
        //代金券id
        voucherOrder.setVoucherId(voucherId);
        //把信息放入阻塞队列
        orderTsaks.add(voucherOrder);
        proxy = (IVoucherOrderService)AopContext.currentProxy();
        //3.返回订单id
        return Result.ok();



        // // 1.查询优惠券
        // SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // // 2.判断秒杀是否开始
        // if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
        //     // 尚未开始
        //     return Result.fail("秒杀尚未开始！");
        // }
        // // 3.判断秒杀是否已经结束
        // if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
        //     // 尚未开始
        //     return Result.fail("秒杀已经结束！");
        // }
        // // 4.判断库存是否充足
        // if (voucher.getStock() < 1) {
        //     // 库存不足
        //     return Result.fail("库存不足！");
        // }
        //
        // //用户id
        // Long userId = UserHolder.getUser().getId();
        // //创建锁对象
        // // SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate,"lock:order:" + userId);
        // RLock lock = redissonClient.getLock("lock:order:" + userId);
        // boolean tryLock = lock.tryLock();
        // if (!tryLock){
        //     //获取锁失败，返回错误信息
        //     return  Result.fail("不允许重复下单");
        // }
        // try {
        //     IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
        //     return proxy.createVoucherOrder(voucherId);
        // } finally {
        //     lock.unlock();
        // }
    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //一人一单
        //用户id
        Long userId = voucherOrder.getUserId();
            int count = query().eq("user_id", userId)
                    .eq("voucher_id", voucherOrder.getVoucherId()).count();
            if (count > 0) {
                //用户已经购买过了
                log.error("用户已经购买过了");
                return;
            }
            //扣减库存
            boolean success = seckillVoucherService.update()
                    .setSql("stock= stock -1")
                    .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0).update(); //where id = ? and stock > 0
            if (!success) {
                //扣减库存
                log.error("库存不足！");
                return;
            }
            save(voucherOrder);
    }
}
