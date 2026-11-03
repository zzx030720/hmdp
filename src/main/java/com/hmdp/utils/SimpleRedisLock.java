package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock {

    private StringRedisTemplate stringRedisTemplate;
    private String name;

    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";

    //采用静态代码块
    /**
     * 声明一个静态常量 UNLOCK_SCRIPT,类型是 Spring Data Redis 提供的 DefaultRedisScript<Long>。
     * DefaultRedisScript 是 Spring 对"一段 Lua 脚本"的封装对象,
     * 可以像普通资源一样加载、缓存,再交给 redisTemplate.execute(...) 执行。
     */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    /**
     * 尝试获取锁,主要使用的还是setnx(SET if Not eXists)命令
     * "所有 JVM 实例连的是同一个 Redis" 是用 Redis 实现分布式锁的前提条件。
     * 但仅"同一个 Redis"还不够,真正保证互斥的是 SETNX 命令的原子性 + Redis
     * 单线程执行命令模型
     */
    @Override
    public boolean tryLock(long timeoutSec) {
        //获取当前线程标识,用于传给value,
        // 这里 value 传 threadId 而不是固定的 "1"，是为了在释放锁时能判断"锁是谁加的"，防止线程误删别人的锁。
        //问题背景：误删锁的场景
        //假设 value 只写 "1"，考虑这种情况：
        //线程 A 获取锁成功（lock:order:1 → "1"，假设过期 10 秒）
        //线程 A 业务执行太慢，超过 10 秒，锁自动过期被 Redis 删除
        //线程 B 此时获取锁成功（lock:order:1 → "1"）
        //线程 A 业务终于执行完，调用 unlock() 直接 del lock:order:1 —— 它删掉的是线程 B 的锁！
        //线程 C 又能获取锁，和线程 B 并发执行，互斥被破坏

        /**
         *因为在测试时,在测试双线程时,第一个线程获取锁后我在redis中删掉他的锁,
         * 当时显示进程号是71,之后debug第二个进程,获取新的锁,在redis中查看锁的属性后,
         * 发现前缀是不同但是-后的getID也是71这是因为:
         Thread.getId() 是 JVM 内的局部序号,两个跑着相同代码、
         相同线程池配置的 Spring Boot 实例几乎必然给"处理同一个接口的线程"编出同一个号(71)。
         区分实例的职责由前面的 UUID 承担,所以"前缀不同、后缀相同"正是设计预期的样子,不是异常
         */
        String threadId = ID_PREFIX+Thread.currentThread().getId();
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success); //自动拆箱，可能会报错，因为Boolean的错误是null
    }

    //lua脚本实现释放锁
    @Override
    public void unlock() {
        //调用lua脚本,这边用了单元素集合,用于传递key
        stringRedisTemplate.execute(UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId());
    }


//    @Override
//    public void unlock() {
//        //获取线程标识,用于判断是否是当前线程加的锁
//        String threadId = ID_PREFIX+String.valueOf(Thread.currentThread().getId());
//        //获取锁标识
//        String lockId = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
//        //判断是否相同
//        if (lockId != null && lockId.equals(threadId)) {
//            //释放锁
//            stringRedisTemplate.delete(KEY_PREFIX + name);
//        }
//    }
}
