package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.util.*;
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
        // 解决缓存穿透
        // Shop shop = queryWithPassThrough(id);
        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, id2 -> getById(id2), CACHE_NULL_TTL, TimeUnit.MINUTES);

        // 互斥锁解决缓存击穿
        // Shop shop = queryWithMutex(id);
        // Shop shop = cacheClient
        //         .queryWithMutex(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 逻辑过期解决缓存击穿
        // Shop shop = cacheClient
        //         .queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, 20L, TimeUnit.SECONDS);

        if (shop == null) {
            return Result.fail("没有该商户");
        }
        return Result.ok(shop);
    }

    //1缓存穿透
    // public Shop queryWithPassThrough(Long id){
    //     //1.从redis查询商户缓存
    //     String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
    //     //2.判断是否存在
    //     if (StrUtil.isNotBlank(shopJson)) {
    //         //3.存在直接返回
    //         Shop shop = JSONUtil.toBean(shopJson, Shop.class);
    //         return shop;
    //     }
    //     //判断redis中的是否是空值
    //     if (shopJson != null) {
    //         return null;
    //     }
    //     //4.不存在，根据id查询数据库
    //     Shop shop = getById(id);
    //     //4.1不存在，返回错误
    //     if (shop == null) {
    //         //将空值写入redis
    //         stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
    //         //返回错误信息
    //         return null;
    //     }
    //     //4.2存在，写入redis
    //     stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
    //     //5.返回数据
    //     return shop;
    // }

    //2.互斥锁缓存击穿
    // public Shop queryWithMutex(Long id){
    //     //1.从redis查询商户缓存
    //     String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
    //     //2.判断是否存在
    //     if (StrUtil.isNotBlank(shopJson)) {
    //         //3.存在直接返回
    //         Shop shop = JSONUtil.toBean(shopJson, Shop.class);
    //         return shop;
    //     }
    //     //判断redis中的是否是空字符串
    //     if (shopJson != null) {
    //         //不为空，证明存的空字符串
    //         return null;
    //     }
    //     //4.不存在，根据id查询数据库
    //     //获取互斥锁
    //     Shop shop = null;
    //     try {
    //         boolean idLock = tryLock(LOCK_SHOP_KEY + id);
    //         //判断是否获取锁成功
    //         if (!idLock) {
    //             //失败--休眠并重试
    //             Thread.sleep(50);
    //             queryWithMutex(id);
    //         }
    //         //成功--查询数据库
    //         shop = getById(id);
    //         //4.1查询不存在，返回错误
    //         if (shop == null) {
    //             //将空值写入redis
    //             stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
    //             //返回错误信息
    //             return null;
    //         }
    //         //4.2存在，写入redis
    //         stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
    //     } catch (InterruptedException e) {
    //         throw new RuntimeException(e);
    //     }finally {
    //         //释放互斥锁
    //         unLock(LOCK_SHOP_KEY + id);
    //     }
    //
    //     //5.返回数据
    //     return shop;
    // }

    // private boolean tryLock(String key){
    //     Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
    //     return BooleanUtil.isTrue(flag);
    // }
    // private void unLock(String key){
    //     stringRedisTemplate.delete(key);
    // }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        //1.更新数据库
        updateById(shop);
        //2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY+shop.getId());
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
        // GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo() // GEOSEARCH key BYLONLAT x y BYRADIUS 10 WITHDISTANCE
        //         .search(
        //                 key,
        //                 GeoReference.fromCoordinate(x, y),
        //                 new Distance(5000),
        //                 RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
        //         );

        // 使用GEORADIUS命令：GEORADIUS key 经度 纬度 半径 单位 WITHDISTANCE LIMIT end
        // 定义一个唯一的虚拟成员标识（避免冲突）
        String virtualMember = "temp_center_" + System.currentTimeMillis();
        // 临时插入虚拟成员到Redis（经纬度为目标中心点x,y）
        stringRedisTemplate.opsForGeo().add(key, new Point(x, y), virtualMember);
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .radius(
                        key,
                        virtualMember, // 传入虚拟成员作为中心点
                        new Distance(5000), // 半径5000单位（默认米）
                        RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
                                .includeDistance() // 包含距离信息
                                .sortAscending() // 按距离升序（默认就是升序，可省略）
                                .limit(end) // 限制返回前end条结果
                );
        // 删除临时插入的虚拟成员，避免污染数据
        stringRedisTemplate.opsForGeo().remove(key, virtualMember);
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
            // 关键：只处理真实的店铺ID（排除虚拟成员）
            if (!virtualMember.equals(shopIdStr)) {
                ids.add(Long.valueOf(shopIdStr));
                // 4.3.获取距离
                Distance distance = result.getDistance();
                distanceMap.put(shopIdStr, distance);
            }
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
}
