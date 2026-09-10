package com.bank.vam.defectfixsmoketest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * This file exists solely to exercise the defect-fix pipeline's backend
 * test-failure detection step. It intentionally contains no defects.
 */
class SmokeTestCalculatorTest {

    @Test
    void addReturnsTheSumOfBothArguments() {
        SmokeTestCalculator calculator = new SmokeTestCalculator();

        assertEquals(5, calculator.add(2, 3));
    }

    private static class SmokeTestCalculator {
        int add(int a, int b) {
            return a + b;
        }
    }
}
