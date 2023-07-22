package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.constant.RedisConstants.*;
import static com.hmdp.constant.RedisConstants.CACHE_SHOP_KEY;

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
    public Result queryList() {
        //从redis中查询商户类型缓存
        String typeListJson = stringRedisTemplate.opsForValue().get(CACHE_SHOPTYPE_KEY);

        if (StrUtil.isNotBlank(typeListJson)){
            //查询到，直接返回
            List<ShopType> typeList = JSONUtil.toList(JSONUtil.parseArray(typeListJson), ShopType.class);
            return Result.ok(typeList);
        }
        //未查询到，查询数据库
        List<ShopType> typeList = this
                .query().orderByAsc("sort").list();

        //查询不到，返回错误信息
        if (typeList == null){
            return Result.fail("商户类型列表不存在");
        }
        //查询到，存入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOPTYPE_KEY,JSONUtil.toJsonStr(typeList),30L, TimeUnit.MINUTES);
        //返回信息
        return Result.ok(typeList);
    }
}
