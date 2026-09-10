package com.bank.vam.defectfixsmoketest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SmokeTestCalculatorTest {

    private final SmokeTestCalculator calculator = new SmokeTestCalculator();

    @Test
    void multiplyReturnsTheProductOfBothArguments() {
        assertThat(calculator.multiply(3, 4)).isEqualTo(12);
    }
}
