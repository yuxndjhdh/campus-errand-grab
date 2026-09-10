package com.yu.errand.unit;

import com.yu.errand.domain.OrderStatus;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StateMachineTableTest {
    @Test
    void onlyDocumentedTransitionsAreAllowed() {
        assertTrue(allowed(OrderStatus.PUBLISHED, OrderStatus.TAKEN));
        assertTrue(allowed(OrderStatus.TAKEN, OrderStatus.DELIVERED));
        assertTrue(allowed(OrderStatus.DELIVERED, OrderStatus.SETTLED));
        assertTrue(allowed(OrderStatus.PUBLISHED, OrderStatus.CANCELLED));
        assertTrue(allowed(OrderStatus.TAKEN, OrderStatus.CANCELLED));
        assertFalse(allowed(OrderStatus.SETTLED, OrderStatus.TAKEN));
        assertFalse(allowed(OrderStatus.CANCELLED, OrderStatus.PUBLISHED));
        assertFalse(allowed(OrderStatus.DELIVERED, OrderStatus.CANCELLED));
    }

    private boolean allowed(OrderStatus from, OrderStatus to) {
        return switch (from) {
            case PUBLISHED -> EnumSet.of(OrderStatus.TAKEN, OrderStatus.CANCELLED).contains(to);
            case TAKEN -> EnumSet.of(OrderStatus.DELIVERED, OrderStatus.CANCELLED).contains(to);
            case DELIVERED -> to == OrderStatus.SETTLED;
            case SETTLED, CANCELLED -> false;
        };
    }
}

