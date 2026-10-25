package com.example.common;

import java.math.BigDecimal;

public class Money {
    private final BigDecimal amount;

    public Money(BigDecimal amount) {
        this.amount = amount;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public boolean isPositive() {
        return amount != null && amount.signum() > 0;
    }
}
