package com.example.payment;

import com.example.order.OrderService;
import com.example.user.UserService;

public class PaymentService {

    private final UserService userService;
    private final OrderService orderService;

    public PaymentService(UserService userService, OrderService orderService) {
        this.userService = userService;
        this.orderService = orderService;
    }

    public void validateUser(Long userId) {
        if (!userService.isActive(userId)) {
            throw new IllegalStateException("user is not active");
        }
    }

    public void pay(Long userId, Long orderId) {
        validateUser(userId);
        orderService.auditOrder(userId);
    }
}
