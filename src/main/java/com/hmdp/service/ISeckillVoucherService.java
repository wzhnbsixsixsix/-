package com.hmdp.service;

import com.hmdp.entity.SeckillVoucher;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 * 秒杀优惠券表，与优惠券是一对一关系 服务类
 * </p>
 *
 * @author 虎哥
 * @since 2022-01-04
 */

//IService<T> 是 MyBatis-Plus 提供的通用 Service 接口，提供了一套 通用的 CRUD（增删改查）方法，减少重复代码。
//这行代码表示 ISeckillVoucherService 继承了 IService<SeckillVoucher>，提供对 SeckillVoucher 实体的 CRUD（增删改查）功能。
public interface ISeckillVoucherService extends IService<SeckillVoucher> {

}
