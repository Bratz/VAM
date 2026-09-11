package com.bank.vam.defectfixsmoketest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SmokeTestAdderTest {

    private final SmokeTestAdder adder = new SmokeTestAdder();

    @Test
    void addReturnsTheSumOfBothArguments() {
        assertThat(adder.add(3, 4)).isEqualTo(7);
    }
}
