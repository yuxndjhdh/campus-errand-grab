package com.yu.errand.unit;

import com.yu.errand.service.CommissionMath;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommissionMathTest {
    @Test
    void rewardIsExactlySplitForManyAmountsAndRates() {
        for (long reward = 1; reward <= 10_000; reward += 37) {
            for (BigDecimal rate : new BigDecimal[]{new BigDecimal("0"), new BigDecimal("0.01"), new BigDecimal("0.10"), new BigDecimal("0.333"), BigDecimal.ONE}) {
                long commission = CommissionMath.commission(reward, rate);
                assertEquals(reward, reward - commission + commission);
            }
        }
    }
}

