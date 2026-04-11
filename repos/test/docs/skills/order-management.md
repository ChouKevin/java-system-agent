# order-management

## 概述

訂單資料管理，使用 MyBatis-Plus 提供 CRUD 與自訂查詢。

## 相關類別

| 類型 | Class | 說明 |
|------|-------|------|
| Entity | `com.example.mybatisplus.Order` | 訂單實體（orders 表） |
| Mapper | `com.example.mybatisplus.OrderMapper` | 資料存取層，含自訂 `findByOrderNo` 查詢 |
| Service 介面 | `com.example.mybatisplus.IOrderService` | 業務介面，擴充 `IService<Order>` |
| Service 實作 | `com.example.mybatisplus.OrderServiceImpl` | 業務實作，委派 `baseMapper` 執行查詢 |

## 資料表

| 表名 | 欄位 |
|------|------|
| `orders` | `id`, `order_no`, `status`, `tenant_id` |

## SQL

```sql
-- OrderMapper.xml: findByOrderNo
SELECT id, order_no, status, tenant_id
FROM orders
WHERE order_no = #{orderNo}
```
