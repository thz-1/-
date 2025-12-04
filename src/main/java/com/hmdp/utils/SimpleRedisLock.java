package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * @author thz
 */
public class SimpleRedisLock implements ILock {

    private StringRedisTemplate stringRedisTemplate;
    private String name;

    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    public static final String KEY_PREFIX = "lock:";
    public static final String ID_PREFIX = UUID.randomUUID().toString() + "-";
    @Override
    public boolean tryLock(long timeoutSec) {
        //获取线程标识
        String threadid = ID_PREFIX + Thread.currentThread().getId();
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadid , timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {
        //获取线程标识
        String threadid = ID_PREFIX + Thread.currentThread().getId();
        //获取锁中的表示
        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        if (threadid.equals(id)) {
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }

    }
}
