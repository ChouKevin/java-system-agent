package com.example.listener;

import org.springframework.amqp.rabbit.annotation.RabbitListener;

public class MqListenerService {

    @RabbitListener(queues = "demo.queue")
    public void onMessage(String body) {
        System.out.println(body);
    }
}
