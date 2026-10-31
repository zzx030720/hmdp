package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import io.netty.util.internal.StringUtil;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryTypeList() {
//        //1.先判断redis中是否存在
//        String shopTypeJson = stringRedisTemplate.opsForValue().get("shopTypeList");
//
//        //2.存在,直接返回,前端要的list<ShopType>格式,redis返回的是String
//        //StrUtil.isNotBlank(shopTypeJson)  // null、""、"   "（纯空白）都算未命中
//        if (StrUtil.isNotBlank(shopTypeJson)) {
//            return Result.ok(JSONUtil.toList(shopTypeJson, ShopType.class));
//        }
//
//        //3.不存在,去数据库中查询
//        //ShopTypeServiceImpl 继承了 ServiceImpl<ShopTypeMapper, ShopType>，
//        // 而 query() 是父类的实例方法,实体的 @TableName 决定表名
//        List<ShopType> shopTypeList = query().orderByAsc("sort").list();
//
//        //4.如果数据库中不存在,返回错误
//        if (shopTypeList == null) {
//            return Result.fail("店铺类型不存在");
//        }
//
//        //5.存在则写回redis
//        stringRedisTemplate.opsForValue().set("shopTypeList", JSONUtil.toJsonStr(shopTypeList));
//        //6.返回结果
//        return Result.ok(shopTypeList);
//    }
        //1.先判断redis中是否存在
        String shopTypeJson = stringRedisTemplate.opsForValue().get("shopTypeList");
        //2.如果存在,直接返回
        if (StrUtil.isNotBlank(shopTypeJson)) {
            return Result.ok(JSONUtil.toList(shopTypeJson, ShopType.class));
        }
        //3.不存在去查找数据库
        List<ShopType> shopTypeList = query().orderByAsc("sort").list();
        //4.数据库不存在则返回错误
        if (shopTypeList == null) {
            return Result.fail("店铺类型不存在");
        }

        //5.数据库存在则写回redis
        stringRedisTemplate.opsForValue().set("shopTypeList", JSONUtil.toJsonStr(shopTypeList));

        //返回数据
        return Result.ok(shopTypeList);
        }
}
