package com.example.mybatisplus;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("orders")
public class Order {
    private Long id;
    private String orderNo;
    private String status;
    private String tenantId;
}
