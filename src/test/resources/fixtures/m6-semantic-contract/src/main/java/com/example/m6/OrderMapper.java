package com.example.m6;

import org.apache.ibatis.annotations.Select;

public interface OrderMapper {

    @Select("SELECT id FROM orders WHERE id = #{orderId}")
    String findById(String orderId);

    @Select("SELECT id FROM orders WHERE id = #{orderId}")
    String defaultOrderLookup(String orderId);
}
