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
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.constant.RedisConstants.*;
import static com.hmdp.constant.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 发送手机验证码
     *
     * @param phone   手机号
     * @param session 会话对象
     * @return
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //判断手机号码格式是否正确
        if (RegexUtils.isPhoneInvalid(phone)){
            //格式不正确返回错误信息
            return Result.fail("手机号码格式不正确");
        }
        //生成验证码
        String code = RandomUtil.randomNumbers(6);
        //保存验证码到session中
//        session.setAttribute(phone,code);
        //保存验证码到redis中
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //发送验证码
        log.debug("发送验证码成功，验证码为：{"+code+"}");
        //返回成功信息
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //判断手机号码格式是否正确
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)){
            //格式不正确返回错误信息
            return Result.fail("手机号码格式不正确");
        }

        String code = loginForm.getCode();
        //从redis中获取验证码
//        Object cachaCode = session.getAttribute(phone);
        String cachaCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        //判断验证码是否正确
        if (cachaCode == null || !cachaCode.toString().equals(code)){
            //验证码不正确返回错误信息
            return Result.fail("验证码不正确");
        }
        //根据手机号查询用户
        User user = this.lambdaQuery().eq(User::getPhone, phone).one();
        //判断用户是否存在
        if (user == null){
            //不存在创建用户
            user = createUserWithPhone(phone);
        }

        //保存用户信息到redis

        //随机生成token，作为登录令牌
        String token = UUID.randomUUID().toString(true);
        //将user对象转为hashMap存储
        UserDTO userDTO =BeanUtil.copyProperties(user, UserDTO.class);
//        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        //存储
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);
//        session.setAttribute("user",BeanUtil.copyProperties(user,UserDTO.class));
        //设置token过期时间
        stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL,TimeUnit.MINUTES);
        //返回结果
        return Result.ok(token);

    }

    //签到
    @Override
    public Result sign() {
        //获取登录用户
        Long userId = UserHolder.getUser().getId();
        //获取日期
        LocalDateTime now = LocalDateTime.now();
        //拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        //获取今天是本月第几天
        int dayOfMonth = now.getDayOfMonth();
        //写入redis SETBIT key offset 1
        stringRedisTemplate.opsForValue().setBit(key,dayOfMonth-1,true);
        return Result.ok();

    }

    @Override
    public Result signCount() {
        //获取登录用户
        Long userId = UserHolder.getUser().getId();
        //获取日期
        LocalDateTime now = LocalDateTime.now();
        //拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        //获取今天是本月第几天
        int dayOfMonth = now.getDayOfMonth();
        //获取本月截至今天位置的签到记录，返回结果为一个十进制的数字 BITFIELD sign:1:202307 GET u20 0
        List<Long> results = stringRedisTemplate.opsForValue().bitField(key,
                BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));
		if (results == null || results.isEmpty()) {
            //没有任何签到结果
            return Result.ok(0);
        }
        Long num = results.get(0);
        if (num == null||num == 0) {
            //没有任何签到结果
            return Result.ok(0);
        }
        int count = 0;
        //循环遍历
        while (true) {
            //让这个数字和1作与运算，得到数字的最后一位，如果是1，说明签到了，如果是0，说明没有签到
            if ((num & 1) == 1) {
                //如果是1，累加到签到次数中
                count++;
            }else {
                //如果是0，未签到，结束
                break;
            }
            //把数字右移一位，抛弃最后一位，继续循环
            num = num >>> 1;
        }
        //返回结果
        return Result.ok(count);

    }

    private User createUserWithPhone(String phone) {
        //创建用户
        User user = new User();
        user.setPhone(phone);
//        user.setNickName(USER_NICK_NAME_PREFIX+ phone.substring(7));
        user.setNickName(USER_NICK_NAME_PREFIX+ RandomUtil.randomString(10));
        //保存用户
        save(user);
        return user;

    }
}
