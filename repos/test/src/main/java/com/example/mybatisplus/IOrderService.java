package com.example.mybatisplus;

import com.baomidou.mybatisplus.extension.service.IService;

public interface IOrderService extends IService<Order> {

    Order getOrderByNo(String orderNo);
}
