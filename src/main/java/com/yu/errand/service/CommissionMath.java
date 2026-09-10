package com.yu.errand.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class CommissionMath {
    private CommissionMath() {}

    public static long commission(long rewardCents, BigDecimal rate) {
        if (rewardCents < 0 || rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("reward/rate out of range");
        }
        return BigDecimal.valueOf(rewardCents).multiply(rate).setScale(0, RoundingMode.FLOOR).longValueExact();
    }
}

