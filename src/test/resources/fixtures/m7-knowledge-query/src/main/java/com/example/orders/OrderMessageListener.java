package com.example.orders;

import org.springframework.amqp.rabbit.annotation.RabbitListener;

public final class OrderMessageListener {

    private final OrderWorkflow workflow;

    public OrderMessageListener(OrderWorkflow workflow) {
        this.workflow = workflow;
    }

    @RabbitListener(queues = "orders")
    public void onOrderRequested(OrderRequested event) {
        workflow.processOrder(event.request());
    }
}
