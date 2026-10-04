package com.bank.vam.entity;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * bankCode is the home-bank check, so it has to be a BIC and nothing else.
 *
 * <p>ShadowAccountService copies {@code PhysicalAccount.bankCode} into
 * {@code VirtualAccount.bankSwift} when a shadow is created, and {@code HomeBankProperties.matches()}
 * is an exact case-insensitive equality against {@code VAM_HOME_BANK_BIC}. A value that is not a BIC
 * can never match any configuration, so the account goes silently ineligible to back a program instead
 * of failing visibly. PA-GBP-002 held the string "HSBC" and was unusable under every market profile --
 * including the UK one, where HSBC was the very home bank it should have matched.
 *
 * <p>The database carries the same rule as a CHECK constraint, which is the half that also covers
 * hand-run SQL. This guards the application path, where the message is readable.
 */
class BankCodeIsABicTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) factory.close();
    }

    private boolean accepts(String bankCode) {
        PhysicalAccount pa = PhysicalAccount.builder().bankCode(bankCode).build();
        return validator.validateProperty(pa, "bankCode").isEmpty();
    }

    @Test
    void aBankNameIsRejected() {
        // the live value that made PA-GBP-002 permanently ineligible
        assertThat(accepts("HSBC")).isFalse();
        assertThat(accepts("Emirates NBD")).isFalse();
    }

    @Test
    void realBicsAreAcceptedAtBothLengths() {
        assertThat(accepts("HBUKGB4BXXX")).isTrue();   // 11, with branch code
        assertThat(accepts("HBUKGB4B")).isTrue();      // 8, without
        assertThat(accepts("EBILAEADXXX")).isTrue();   // the configured home bank on OCI
    }

    @Test
    void caseIsNotTheTestBecauseTheComparisonIgnoresIt() {
        // HomeBankProperties.matches() is equalsIgnoreCase, so a lowercase BIC still works and must
        // not be rejected here -- the rule is shape, not casing.
        assertThat(accepts("hbukgb4bxxx")).isTrue();
    }

    @Test
    void almostBicsAreRejected() {
        assertThat(accepts("HBUK1B4BXXX")).isFalse();  // digit inside the 6-letter bank code
        assertThat(accepts("HBUKGB4")).isFalse();      // 7 characters
        assertThat(accepts("HBUKGB4BXX")).isFalse();   // 10: branch code must be 3 or absent
        assertThat(accepts("HBUKGB4BXXXX")).isFalse(); // 12
    }

    @Test
    void anUnknownBicIsAllowedToBeAbsent() {
        // The column is nullable: an account whose BIC is not recorded is simply never the home bank.
        assertThat(accepts(null)).isTrue();
    }
}
