package com.example.mybatisplus;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

/**
 * Mapper interface for Order.
 * Custom query methods are defined in OrderMapper.xml.
 */
public interface OrderMapper extends BaseMapper<Order> {

    Order findByOrderNo(@Param("orderNo") String orderNo);
}
