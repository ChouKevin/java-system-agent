package com.example.orders;

import org.springframework.scheduling.annotation.Scheduled;

public final class OrderRecoveryJob {

    private final OrderWorkflow workflow;

    public OrderRecoveryJob(OrderWorkflow workflow) {
        this.workflow = workflow;
    }

    @Scheduled(fixedDelay = 60_000L)
    public void retryPendingOrders() {
        workflow.processOrder(new OrderRequest("pending-order"));
    }
}
