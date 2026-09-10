package com.bank.vam.defectfixsmoketest;

/**
 * Throwaway class for testing the automated defect-fix pipeline's backend path
 * (see tasks/defect-autofix-pipeline-design.md). Safe to delete once the
 * pipeline has picked up and fixed the deliberate bug below.
 */
public class SmokeTestReverser {

    // Deliberately wrong: returns the input unchanged instead of reversing it.
    public String reverse(String input) {
        return input;
    }
}
