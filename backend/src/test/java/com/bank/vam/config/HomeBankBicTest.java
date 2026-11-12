package com.bank.vam.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** An 8-character BIC is its bank's head office: the same as the 11-character form ending XXX. */
class HomeBankBicTest {

    @Test
    void eightAndElevenCharacterFormsOfTheHeadOfficeAreTheSameBank() {
        assertTrue(HomeBankProperties.sameBank("HBUKGB4BXXX", "HBUKGB4B"));
        assertTrue(HomeBankProperties.sameBank("hbukgb4b", "HBUKGB4BXXX"));
    }

    @Test
    void otherBanksAndOtherBranchesAreNot() {
        assertFalse(HomeBankProperties.sameBank("HBUKGB4BXXX", "CHASUS33"));
        assertFalse(HomeBankProperties.sameBank("HBUKGB4BXXX", "HBUKGB4B123"));
        assertFalse(HomeBankProperties.sameBank(null, "HBUKGB4B"));
    }
}
