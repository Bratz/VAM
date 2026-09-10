package com.bank.vam.defectfixsmoketest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SmokeTestReverserTest {

    private final SmokeTestReverser reverser = new SmokeTestReverser();

    @Test
    void reverseReturnsTheInputBackwards() {
        assertThat(reverser.reverse("hello")).isEqualTo("olleh");
    }
}
