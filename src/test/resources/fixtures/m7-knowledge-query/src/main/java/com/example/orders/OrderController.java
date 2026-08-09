package com.example.orders;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orders")
public final class OrderController {

    private final OrderWorkflow workflow;

    public OrderController(OrderWorkflow workflow) {
        this.workflow = workflow;
    }

    @PostMapping
    public void submitOrder(OrderRequest request) {
        workflow.processOrder(request);
    }
}
