package com.example.mybatisplus;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * Service implementation for Order.
 *
 * <p>Extends {@code ServiceImpl<OrderMapper, Order>}, which injects
 * {@code baseMapper} as an {@code OrderMapper} instance.
 * Custom business methods call {@code baseMapper} directly for queries
 * that are defined in {@code OrderMapper.xml}.
 */
@Service
public class OrderServiceImpl extends ServiceImpl<OrderMapper, Order> implements IOrderService {

    @Override
    public Order getOrderByNo(String orderNo) {
        return baseMapper.findByOrderNo(orderNo);
    }
}
