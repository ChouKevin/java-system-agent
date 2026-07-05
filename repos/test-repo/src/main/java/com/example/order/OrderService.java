package com.example.order;

import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.math.BigDecimal;
import java.util.List;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final InventoryClient inventoryClient;
    private final PaymentClient paymentClient;
    private final ShippingScheduler shippingScheduler;

    public OrderService(OrderRepository orderRepository,
                        InventoryClient inventoryClient,
                        PaymentClient paymentClient,
                        ShippingScheduler shippingScheduler) {
        this.orderRepository = orderRepository;
        this.inventoryClient = inventoryClient;
        this.paymentClient = paymentClient;
        this.shippingScheduler = shippingScheduler;
    }

    public OrderController.OrderResponse createOrder(OrderController.CheckoutRequest request) {
        assertCheckoutItems(request.items());
        inventoryClient.assertAvailable(request.items());
        BigDecimal totalAmount = calculateTotalAmount(request.items());
        String orderId = orderRepository.createPendingOrder(
                request.customerId(), request.shippingAddress(), totalAmount);
        inventoryClient.reserve(orderId, request.items());
        String paymentTransactionId = paymentClient.authorize(orderId, request.paymentMethodId(), totalAmount);
        orderRepository.attachPaymentTransaction(orderId, paymentTransactionId);
        return new OrderController.OrderResponse(orderId, paymentTransactionId, totalAmount);
    }

    public OrderController.PaymentConfirmationResponse confirmPayment(String orderId, String paymentTransactionId) {
        PaymentResult paymentResult = paymentClient.confirm(orderId, paymentTransactionId);
        orderRepository.markPaid(orderId, paymentResult.confirmedAmount());
        String shippingTaskId = shippingScheduler.schedule(orderId);
        orderRepository.attachShippingTask(orderId, shippingTaskId);
        return new OrderController.PaymentConfirmationResponse(orderId, shippingTaskId, "READY_TO_SHIP");
    }

    private void assertCheckoutItems(List<OrderController.OrderItemRequest> items) {
        if (CollectionUtils.isEmpty(items)) {
            throw new IllegalArgumentException("checkout items must not be empty");
        }
    }

    private BigDecimal calculateTotalAmount(List<OrderController.OrderItemRequest> items) {
        return items.stream()
                .map(item -> item.unitPrice().multiply(BigDecimal.valueOf(item.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
