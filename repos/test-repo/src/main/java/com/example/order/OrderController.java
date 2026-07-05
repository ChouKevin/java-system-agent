package com.example.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public OrderResponse createOrder(@Valid @RequestBody CheckoutRequest request) {
        return orderService.createOrder(request);
    }

    @PostMapping("/{orderId}/payments/confirm")
    public PaymentConfirmationResponse confirmPayment(
            @PathVariable String orderId,
            @Valid @RequestBody PaymentConfirmationRequest request) {
        return orderService.confirmPayment(orderId, request.paymentTransactionId());
    }

    public record CheckoutRequest(
            @NotBlank String customerId,
            @NotEmpty List<OrderItemRequest> items,
            @NotBlank String shippingAddress,
            @NotBlank String paymentMethodId) {
    }

    public record OrderItemRequest(
            @NotBlank String sku,
            @Positive int quantity,
            @Positive BigDecimal unitPrice) {
    }

    public record PaymentConfirmationRequest(@NotBlank String paymentTransactionId) {
    }

    public record OrderResponse(String orderId, String paymentTransactionId, BigDecimal totalAmount) {
    }

    public record PaymentConfirmationResponse(String orderId, String shippingTaskId, String status) {
    }
}
