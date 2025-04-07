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
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author wzh
 * @since 2025-3-10
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }
        // 3.符合，生成验证码
        String code = RandomUtil.randomNumbers(6);

        // 4.保存验证码到 redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        // 5.发送验证码
        log.debug("发送短信验证码成功，验证码：{}", code);
        // 返回ok
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {//点击登陆按钮会触发
        String phone = loginForm.getPhone();
        //1.校验手机号(用自定义utils下的RegexUtils.isPhoneInvalid(phone)功能)
        if (RegexUtils.isPhoneInvalid(phone)) {
            //2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误");
        }

        //3.从redis中获取验证码校验
//        Object cachecode = session.getAttribute("code");//刚刚sendCode函数保存到session的code
        String cachecode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if (cachecode == null || !cachecode.equals(code)) {
            //不一致，报错
            return Result.fail("验证码错误");
        }

        //4.一致，根据手机号查询用户
        User user = query().eq("phone", phone).one();//用的Mybatis Plus下的query(),等价于slect * from tb_user where phone = ?
        //5.判断用户是否存在
        if (user == null) {
            //6.不存在，创建用户
            user = createUserWithPhone(phone);
        }
        //7.保存用户到redis并返回ok
        //7.1随机生成token，作为登陆令牌
        String token = UUID.randomUUID().toString(true);
        //7.2将User对象转为UserDTO并且存入userMap
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);

        //如果UserDTO中存在数值类型字段（如Long类型的id），
        //会导致存入Redis时StringRedisTemplate要求所有值为String类型
        //Map<String, Object> userMap = {
        //    "id": "10001",          // 用户ID（Long→String）
        //    "nickName": "user_abc", // 昵称（String）
        //    "icon": "avatar.jpg",   // 头像路径（String）
        //    "phone": "13812345678"  // 手机号（String）
        //    // 其他UserDTO字段...
        //}
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()//允许对key和value作自定义，相当于开启一个自定义模式
                        .setIgnoreNullValue(true)//忽略空属性
                        //对字段值进行判断，修改
                        .setFieldValueEditor((fieldName, fieldValue) ->
                                fieldValue.toString()));


        //7.3存储
//        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        //7.4设置有效期
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);
        //返回token
        return Result.ok(token);
    }


    private User createUserWithPhone(String phone) {
        //创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName("user" + RandomUtil.randomString(5));
        //保存用户,到数据库
        save(user);
        return user;
    }
}
