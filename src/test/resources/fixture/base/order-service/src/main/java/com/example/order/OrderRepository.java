package com.example.order;

import com.example.common.Money;

public interface OrderRepository {
    void save(Long userId, Money price);
}
