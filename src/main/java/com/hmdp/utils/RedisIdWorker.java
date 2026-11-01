package com.hmdp.utils;

import jakarta.annotation.Resource;
import org.springframework.cglib.core.Local;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {

    //开始时间戳
    private static final long START_STAMP = 1667217360L;
    //序列号位数
    private static final int COUNT_BITS = 32;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    //生成随机不重复ID
    public long nextId(String keyPrefix) {
        //1. 生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long timestamp = now.toEpochSecond(ZoneOffset.of("+8"));
        timestamp -= START_STAMP;
        //2. 生成序列号,
        // 这行返回一个 Long 类型的纯数字：1、2、3……也就是“今天这个业务类型的第几次自增”。
        Long sequence = stringRedisTemplate.opsForValue().increment("icr"+keyPrefix + now.format(DateTimeFormatter.ofPattern("yyyyMMdd")));
        //3. 组合,时间戳左移出序列号的位次,long / int 在 JVM 内存里从来都是二进制补码
        return timestamp << COUNT_BITS | sequence;

    }

    public static void main(String[] args) {
        LocalDateTime now = LocalDateTime.of(2022, 10, 31, 19, 56, 0);
        long epochSecond = now.toEpochSecond(ZoneOffset.of("+8"));
        System.out.println(epochSecond);
    }
}
