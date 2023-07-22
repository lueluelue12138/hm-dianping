package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.constant.RedisConstants.*;
import static com.hmdp.constant.RedisConstants.CACHE_SHOP_TTL;

@Component
@Slf4j
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 存入所有类型数据到redis
     * @param key 键
     * @param value 值
     * @param time 过期时间
     * @param unit 时间单位
     */
    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    /**
     * 存入所有类型数据到redis,设置逻辑过期时间
     * @param key 键
     * @param value 值
     * @param time 逻辑过期时间
     * @param unit 时间单位
     */
    public void setWithLogicExpire(String key,Object value,Long time,TimeUnit unit){
        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        redisData.setData(value);
        //存入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    //使用set null解决缓存穿透
    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback,Long time,TimeUnit unit){
        String key = keyPrefix + id;
        //从redis中查询商户缓存
        String json = stringRedisTemplate.opsForValue().get(key);

        if (StrUtil.isNotBlank(json)){
            //查询到，直接返回
            return JSONUtil.toBean(json,type);
        }
        //判断命中的是否是空值
        if (json!=null){
            //返回一个空值
            return null;
        }

        //未查询到，查询数据库
        R r = dbFallback.apply(id);

        //查询不到，返回错误信息
        if (r == null){
            //存入空值，防止缓存穿透
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //查询到，存入redis
        set(key,r,time,unit);

        return r;
    }

    //定义用于重建缓存的线程池
    private static final ExecutorService CACHE_REBUILD_POOL = Executors.newFixedThreadPool(10);
    public <R,ID> R queryWithLogicExpire(String keyPrefix,ID id,Class<R> type,Function<ID,R> dbFallback,Long time,TimeUnit unit){
        String key = keyPrefix + id;
        //从redis中查询商户缓存
        String json = stringRedisTemplate.opsForValue().get(key);

        if (StrUtil.isBlank(json)){
            //查询不到，直接返回空
            return  null;
        }
        //查询到，判断逻辑过期时间
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())){
            //如果过期时间大于当前时间，未过期，则直接返回
            return r;
        }
        //如果过期时间小于当前时间，已过期，需要缓存重建
        //尝试获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        if (!isLock){
            //如果获取锁失败，则直接返回旧数据
            return r;
        }
        //由于多线程，获取锁成功后，需要再次判断缓存是否过期
        json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)){
            //判断缓存是否过期，如果缓存未过期，则直接返回
            RedisData redisData2 = JSONUtil.toBean(json, RedisData.class);
            LocalDateTime expireTime2 = redisData2.getExpireTime();
            if (expireTime2.isAfter(LocalDateTime.now())){
                return JSONUtil.toBean((JSONObject) redisData2.getData(), type);
            }

        }
        //如果获取锁成功，则开启新的独立线程重建缓存，返回旧数据
        CACHE_REBUILD_POOL.submit(()->{
            try {
                //重建缓存
                //查询数据库
                R r1 = dbFallback.apply(id);
                //写入redis
                setWithLogicExpire(key,r1,time,unit);
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                //释放互斥锁
                unLock(lockKey);
            }
        });

        //先返回过期的旧数据
        return r;
    }
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }




}
