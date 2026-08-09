package com.example.orders;

public final class DefaultOrderWorkflow implements OrderWorkflow {

    private final OrderRepository repository;

    public DefaultOrderWorkflow(OrderRepository repository) {
        this.repository = repository;
    }

    @Override
    public void processOrder(OrderRequest request) {
        repository.save(request);
    }
}
