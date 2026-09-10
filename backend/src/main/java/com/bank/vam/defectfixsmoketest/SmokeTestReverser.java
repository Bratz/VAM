package com.bank.vam.defectfixsmoketest;

/**
 * Throwaway class for testing the automated defect-fix pipeline's backend path
 * (see tasks/defect-autofix-pipeline-design.md). Safe to delete once the
 * pipeline has picked up and fixed the deliberate bug below.
 */
public class SmokeTestReverser {

    public String reverse(String input) {
        return new StringBuilder(input).reverse().toString();
    }
}
