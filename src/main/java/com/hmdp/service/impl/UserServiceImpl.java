package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexPatterns;
import com.hmdp.utils.RegexUtils;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    private StringRedisTemplate redisTemplate;
    // 生成验证码
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            //2.如果不符合,返回错误信息
            return Result.fail("手机号格式无效");
        }
        //3.符合,生成验证码,为6为随机数字
        String code = RandomUtil.randomNumbers(6);

        //4.保存验证码到redis
        redisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //5.发送验证码
        log.info("发送验证码：{}给{}", code, phone);
        //6.返回ok
        return Result.ok();
    }

    // 登录
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.校验手机号
        if (RegexUtils.isPhoneInvalid(loginForm.getPhone())) {
            return Result.fail("手机号格式无效");
        }
        //2.校验验证码
        //用前端的验证码和redis中的验证码进行比较
        String redisCode = redisTemplate.opsForValue().get(LOGIN_CODE_KEY + loginForm.getPhone());
        String code = loginForm.getCode();
        //这里判断redisCode是否为null是为了防止原本存的验证码过期或者没有
        if (redisCode == null || !code.equals(redisCode)) {
            //3.不一致,报错
            return Result.fail("验证码错误");
        }

        //这里用到了mybaits-plus技术,简化了数据库操作,
        // query()相当于mybaits中的select * from user where phone = ?;
        //4.一致,根据手机号查询用户,目的是看用户是否存在
        User user = query().eq("phone", loginForm.getPhone()).one();

        //5.判断用户是否存在
        if (user == null) {
            //6.不存在,创建新用户并保存
            user = createUserWithPhone(loginForm.getPhone());
        }

        //7.存在,保存用户信息到redis中,存入的时UserDTO
        //7.1生成token,作为登陆令牌
        String token = UUID.randomUUID().toString(true);
        //7.2将User对象转为HashMap存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        //Map<String, String> userMap = new HashMap<>();
//        userMap.put("id", userDTO.getId().toString());
//        userMap.put("nickName", userDTO.getNickName());
//        userMap.put("icon", userDTO.getIcon());
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO // ① 源对象
                , new HashMap<>(), // ② 目标容器
                CopyOptions.create()  // ③ 拷贝规则
                        .setIgnoreNullValue(false)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));

        //7.3 存储
        // 这里用putAll是因为可以存多个字段value
        redisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, userMap);
        //设置有效期 设置初始寿命
        redisTemplate.expire(LOGIN_USER_KEY + token, LOGIN_USER_TTL, TimeUnit.MINUTES);
        //8. 返回token
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        //1.创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        //2.保存用户,也是mybaits-plus技术
        save(user);
        return user;
    }
}
