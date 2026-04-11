# mq-listener

## 概述

接收 RabbitMQ 訊息佇列的消費者群組。

## 進入點

| 類型 | Class | Method | Queue | 說明 |
|------|-------|--------|-------|------|
| MQ Consumer | `com.example.listener.MqListenerService` | `onMessage(String)` | `demo.queue` | 接收訊息並輸出至 console |

## 呼叫鏈

```
MqListenerService.onMessage(String body)
  └── System.out.println(body)
```
