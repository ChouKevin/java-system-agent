package com.example.strategy;

/**
 * 純 Java class，無 Spring 註解
 * 用於測試 findImplementationsByName 不會過濾非 Spring Bean 的實作
 */
public class CashPaymentStrategy implements PaymentStrategy {

    @Override
    public void processPayment(String userId) {
        System.out.println("cash:" + userId);
    }
}
