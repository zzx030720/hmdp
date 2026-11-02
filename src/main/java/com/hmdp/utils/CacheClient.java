package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

@Component
@Slf4j
public class CacheClient {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    //  JSONUtil.toJsonStr(value) 将value转换为json字符串
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        //把value封装到RedisData中
        RedisData redisData = new RedisData();
        redisData.setData(value);
        //设置过期时间,并且转换单位为秒
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 缓存穿透
     *
     * @param id
     * @return
     */
    //  R表示任意类型,因为返回值是任意类型,Function<ID,R> dbFallback表示根据id查询数据库的函数,返回R型
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type,
                                          Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        //1.从redis查询商铺缓存
        String Json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (StrUtil.isNotBlank(Json)) {
            //3.存在,直接返回,拿出来的是shop对象
            return JSONUtil.toBean(Json, type);
        }

        //判断是否空值,空字符串
        if (Json != null) {
            return null;
        }
        //4.不存在,先根据id查询数据库,因为是工具类不知道具体查询逻辑,所以交给用户传递
        // dbFallback.apply(id) 的字面意思就是：把这个函数执行一下，入参是 id，拿到它返回的 R
        R r = dbFallback.apply(id);

        //5.不存在,返回错误
        if (r == null) {
            //将空值写回redis,给key的过期时间设置随机数,防止缓存击穿
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        //6.存在,写入redis ,存入的是Json对象
//        long ttl = CACHE_SHOP_TTL + ThreadLocalRandom.current().nextLong(-10, 11);
        this.set(key, r, time, unit);
        //7.返回
        return r;
    }

    /**
     * 逻辑过期解决缓存击穿
     *
     * @param id
     * @return
     */

    //构建线程池,存放10个线程
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type,
                                            Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        //1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (StrUtil.isBlank(shopJson)) {
            //3.不存在,返回错误
            return null;
        }
        //4.命中,先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        //5.获取逻辑过期时间,判断是否过期
        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())) {
            //5.1未过期,返回商铺信息
            return r;
        }
        //5.2已过期,需要缓存重建
        //6.缓存重建
        //6.1获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        //6.2判断是否获取成功
        boolean isLocked = tryLock(lockKey);
        if (!isLocked) {
            //6.3失败,返回商铺信息(过期)
            return r;
        }

        String shopJson1 = stringRedisTemplate.opsForValue().get(key);
//        //2.判断是否存在
//        if (StrUtil.isBlank(shopJson1)) {
//            //3.不存在,返回错误
//            return null;
//        }
        //4.命中,先把json反序列化为对象
        RedisData redisData1 = JSONUtil.toBean(shopJson1, RedisData.class);
        //5.获取逻辑过期时间,判断是否过期
        R r1 = JSONUtil.toBean((JSONObject) redisData1.getData(), type);
        LocalDateTime expireTime1 = redisData1.getExpireTime();
        if (expireTime1.isAfter(LocalDateTime.now())) {
            //5.1未过期,返回商铺信息
            return r1;
        }
        //6.4成功,再次查询缓存,开启独立线程,进行缓存重建
        CACHE_REBUILD_EXECUTOR.submit(() -> {
            //重建缓存
            try {
                //先查数据库
                R r2 = dbFallback.apply(id);
                //写入redis
                this.setWithLogicalExpire(key, r2, time, unit);
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                //释放锁
                unlock(lockKey);
            }
        });
        return r;
    }

    /**
     * 尝试获取锁
     *
     * @param key 锁的key
     * @return 是否获取成功
     */
    //只有 key 不存在时才写入。写成功 → 返回 true(抢到锁);
    // key 已存在(别人持有锁)→ 不写,返回 false(抢锁失败)
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.MINUTES);
        return BooleanUtil.isTrue(flag); //类型转换
    }

    /**
     * 释放锁
     *
     * @param key 锁的key
     */
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}

