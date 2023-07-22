package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.constant.SystemConstants;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.constant.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Transactional
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        //使用set null解决缓存穿透
        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY,id,Shop.class,this::getById,CACHE_SHOP_TTL,TimeUnit.MINUTES);
//        Shop shop = queryWithPassThrough(id);

        //使用互斥锁解决缓存击穿
//        Shop shop = queryWithMutex(id);

        //使用逻辑过期解决缓存击穿
//        Shop shop = queryWithLogicExpire(id);
//        Shop shop = cacheClient.queryWithLogicExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, 20L, TimeUnit.SECONDS);
//        Shop shop = cacheClient.queryWithLogicExpire(CACHE_SHOP_KEY, id, Shop.class, id2 ->{
//            try {
//                Thread.sleep(200);
//            } catch (InterruptedException e) {
//                throw new RuntimeException(e);
//            }
//            return this.getById(id2);}, 20L, TimeUnit.SECONDS);


        if (shop == null){
            return Result.fail("商户不存在");
        }
        return Result.ok(shop);
    }

    //使用set null解决缓存穿透
    public Shop queryWithPassThrough(Long id){
        String key = CACHE_SHOP_KEY + id;
        //从redis中查询商户缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        if (StrUtil.isNotBlank(shopJson)){
            //查询到，直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //判断命中的是否是空值
        if (shopJson!=null){
            //返回一个错误信息
//            return Result.fail("店铺信息不存在");
            return null;
        }

        //未查询到，查询数据库
        Shop shop = this.getById(id);

        //查询不到，返回错误信息
        if (shop == null){
            //存入空值，防止缓存穿透
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
//            return Result.fail("商户不存在");
            return null;
        }
        //查询到，存入redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }

    //互斥锁，解决缓存击穿
    public Shop queryWithMutex(Long id){
        String key = CACHE_SHOP_KEY + id;
        //从redis中查询商户缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        if (StrUtil.isNotBlank(shopJson)){
            //查询到，直接返回
            return  JSONUtil.toBean(shopJson, Shop.class);
        }
        //判断命中的是否是空值
        if (shopJson!=null){
            //返回一个错误信息
            return null;
        }
        //实现缓存重构
        //获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            //判断是否获取锁成功
            if (!isLock){
                //如果不成功，则休眠重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            //获取锁成功，二次查询缓存中有无数据
            shopJson = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(shopJson)){
                //查询到，直接返回
                return  JSONUtil.toBean(shopJson, Shop.class);
            }

            //模拟重建延时
            Thread.sleep(200);
            //未查询到，查询数据库
            shop = this.getById(id);

            //查询不到，返回错误信息
            if (shop == null){
                //存入空值，防止缓存穿透
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //查询到，存入redis
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //释放互斥锁
            unLock(lockKey);
        }
        return shop;
    }

    //定义用于重建缓存的线程池
    private static final ExecutorService CACHE_REBUILD_POOL = Executors.newFixedThreadPool(10);
    //逻辑过期，解决缓存击穿
    public Shop queryWithLogicExpire(Long id){
        String key = CACHE_SHOP_KEY + id;
        //从redis中查询商户缓存
        String json = stringRedisTemplate.opsForValue().get(key);

        if (StrUtil.isBlank(json)){
            //查询不到，直接返回空
            return  null;
        }
        //查询到，判断逻辑过期时间
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())){
            //如果过期时间大于当前时间，未过期，则直接返回
            return shop;
        }
        //如果过期时间小于当前时间，已过期，需要缓存重建
        //尝试获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        if (!isLock){
            //如果获取锁失败，则直接返回旧数据
            return shop;
        }
        //由于多线程，获取锁成功后，需要再次判断缓存是否过期
        json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)){
            //判断缓存是否过期，如果缓存未过期，则直接返回
            RedisData redisData2 = JSONUtil.toBean(json, RedisData.class);
            LocalDateTime expireTime2 = redisData2.getExpireTime();
            if (expireTime2.isAfter(LocalDateTime.now())){
                return JSONUtil.toBean((JSONObject) redisData2.getData(), Shop.class);
            }

        }
        //如果获取锁成功，则开启新的独立线程重建缓存，返回旧数据
        CACHE_REBUILD_POOL.submit(()->{
            try {
                //重建缓存
                this.saveShop2Redis(id,20L);
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                //释放互斥锁
                unLock(lockKey);
            }
        });

        //先返回过期的旧数据
        return shop;
    }

    @Override
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null){
            return Result.fail("商户id不能为空");
        }

        //更新数据库
        this.updateById(shop);
        //删除redis缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());
        //返回成功
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 1.判断是否需要根据坐标查询
        if (x == null || y == null) {
            // 不需要坐标查询，按数据库查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }

        // 2.计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // 3.查询redis、按照距离排序、分页。结果：shopId、distance
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo() // GEOSEARCH key BYLONLAT x y BYRADIUS 10 WITHDISTANCE
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        // 4.解析出id
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();

        if (list.size() <= from) {
            // 没有下一页了，结束
            return Result.ok(Collections.emptyList());
        }
        // 4.1.截取 from ~ end的部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            // 4.2.获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            // 4.3.获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        // 5.根据id查询Shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        // 6.返回
        return Result.ok(shops);
    }


    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }

    public void saveShop2Redis(Long id,Long expireSeconds){

        try {
            //查询店铺数据
            Shop shop =getById(id);
//            Thread.sleep(200);
            //封装逻辑过期时间,解决缓存击穿
            RedisData redisData = new RedisData();
            redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
            redisData.setData(shop);
            //写入redis
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(redisData));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }
}
