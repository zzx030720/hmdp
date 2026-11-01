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
import io.netty.util.internal.ThreadLocalRandom;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;
    @Override
    public Result queryById(Long id) {
        //缓存穿透
//        Shop shop = queryWithPassThrough(id);
//        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_NULL_TTL, TimeUnit.MINUTES);
        //互斥锁解决缓存击穿
//        Shop shop = queryWithMutex(id);

        //逻辑过期解决缓存击穿
//        Shop shop = queryWithLogicalExpire(id);

        /**
         * 手动测试逻辑过期有没有实现,首先连续查询ID为1的数据,
         * 终端只返回一次时数据库查询,过了一会再查一次又返回一次数据库查询
         * 说明判断逻辑时间过期了,所以又查了一遍数据库,说明逻辑过期代码没有问题
         */
        Shop shop = cacheClient.
                queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.SECONDS);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        //7.返回
        return Result.ok(shop);
    }


    /**
     * 互斥锁解决缓存击穿
     * @param id
     * @return
     */
    public Shop queryWithMutex(Long id){
        //1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //3.存在,直接返回,拿出来的是shop对象
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }

        //判断是否空值,空字符串,之前已经查过数据库、确认数据不存在并且缓存了空值
        if (shopJson != null) {
            return null;
        }

        //4.实现缓存重建
        //4.1获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            //4.2判断是否获取成功
            boolean isLocked = tryLock(lockKey);
            //4.3失败,则休眠并重试
            if (!isLocked) {
                //4.3失败,则休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }

            //4.4 成功,则查询redis
            //1.从redis查询商铺缓存
            //2.判断是否存在
            if (StrUtil.isNotBlank(stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id))) {
                //3.存在,直接返回,拿出来的是shop对象
                return JSONUtil.toBean(stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id), Shop.class);
            }

            shop = getById(id);
            Thread.sleep(200);
            //5.不存在,返回错误
            if (shop == null) {
                //将空值写回redis,给key的过期时间设置随机数,防止缓存击穿
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //6.存在,写入redis ,存入的是Json对象
            long ttl = CACHE_SHOP_TTL + ThreadLocalRandom.current().nextLong(-10, 11);
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), ttl, TimeUnit.MINUTES);

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //7 释放锁
            unlock(lockKey);
        }
        //8.返回
        return shop;
    }
    /**
     * 缓存穿透
     * @param id
     * @return
     */
    public Shop queryWithPassThrough(Long id){
        //1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //3.存在,直接返回,拿出来的是shop对象
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }

        //判断是否空值,空字符串
        if (shopJson != null) {
            return null;
        }
        //4.不存在,先根据id查询数据库
        Shop shop = getById(id);

        //5.不存在,返回错误
        if (shop == null) {

            //将空值写回redis,给key的过期时间设置随机数,防止缓存击穿
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        //6.存在,写入redis ,存入的是Json对象
        long ttl = CACHE_SHOP_TTL + ThreadLocalRandom.current().nextLong(-10, 11);
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), ttl, TimeUnit.MINUTES);
        //7.返回
        return shop;
    }

    /**
     * 逻辑过期解决缓存击穿
     * @param id
     * @return
     */

    //构建线程池,存放10个线程
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    public Shop queryWithLogicalExpire(Long id){
        //1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //2.判断是否存在
        if (StrUtil.isBlank(shopJson)) {
            //3.不存在,返回错误
            return null;
        }

        //4.命中,先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        //5.获取逻辑过期时间,判断是否过期
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())) {
            //5.1未过期,返回商铺信息
            return shop;
        }
        //5.2已过期,需要缓存重建
        //6.缓存重建
        //6.1获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        //6.2判断是否获取成功
        boolean isLocked = tryLock(lockKey);
        if (!isLocked) {
            //6.3失败,返回商铺信息(过期)
            return shop;
        }

        String shopJson1 = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
//        //2.判断是否存在
//        if (StrUtil.isBlank(shopJson1)) {
//            //3.不存在,返回错误
//            return null;
//        }
        //4.命中,先把json反序列化为对象
        RedisData redisData1 = JSONUtil.toBean(shopJson1, RedisData.class);
        //5.获取逻辑过期时间,判断是否过期
        Shop shop1 = JSONUtil.toBean((JSONObject) redisData1.getData(), Shop.class);
        LocalDateTime expireTime1 = redisData1.getExpireTime();
        if (expireTime1.isAfter(LocalDateTime.now())) {
            //5.1未过期,返回商铺信息
            return shop1;
        }
        //6.4成功,再次查询缓存,开启独立线程,进行缓存重建
        CACHE_REBUILD_EXECUTOR.submit(() -> {
            //重建缓存
            try {
                this.saveShop2Redis(id, 20L);
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                //释放锁
                unlock(lockKey);
            }

        });

        return shop;
    }

    /**
     * 尝试获取锁
     * @param key 锁的key
     * @return 是否获取成功
     */
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.MINUTES);
        return BooleanUtil.isTrue(flag); //类型转换
    }
    /**
     * 释放锁
     * @param key 锁的key
     */
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

    /**
     * 封装逻辑过期时间
     * @param id
     */
    public void saveShop2Redis(Long id, Long expireSeconds) {
        //1.查询商铺信息
        Shop shop = getById(id);
        //2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //3.存入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }
    /**
     * 根据id更新商铺信息
     * @param shop 商铺信息
     * @return 更新结果
     */
    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }

        //1.根据id更新数据库
        updateById(shop);

        //2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }
}
