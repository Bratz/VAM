package com.bank.vam.defectfixsmoketest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SmokeTestCalculatorTest {

    private final SmokeTestCalculator calculator = new SmokeTestCalculator();

    @Test
    void addReturnsTheSumOfBothArguments() {
        assertThat(calculator.add(2, 3)).isEqualTo(5);
    }
}
