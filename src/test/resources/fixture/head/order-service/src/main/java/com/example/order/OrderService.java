package com.example.order;

import com.example.common.Money;
import com.example.user.UserService;

public class OrderService {

    private static final AuditLog log = new AuditLog();

    private final UserService userService;
    private final OrderRepository orderRepository;

    public OrderService(UserService userService, OrderRepository orderRepository) {
        this.userService = userService;
        this.orderRepository = orderRepository;
    }

    public void createOrder(Long userId, Money price) {
        boolean active = userService.isActive(userId);
        if (!active) {
            throw new IllegalStateException("user is not active");
        }
        orderRepository.save(userId, price);
    }

    public void auditOrder(Long userId) {
        boolean active = userService.isActive(userId);
        log.info("audit active=" + active);
    }
}
