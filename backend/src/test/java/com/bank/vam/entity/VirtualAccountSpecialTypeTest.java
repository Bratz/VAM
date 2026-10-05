package com.bank.vam.entity;

import com.bank.vam.entity.VirtualAccount.AccountCategory;
import com.bank.vam.entity.VirtualAccount.VaSpecialType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * specialType used to be a stored column kept in step with accountCategory by hand, and it
 * drifted: three live VAs had specialType=SETTLEMENT with accountCategory=TRANSACTION, so the
 * settlement screen (which filtered on specialType) showed them as configured while the payment
 * resolver (which filters on accountCategory) could never find them. It is derived now, so the
 * two cannot disagree -- this pins that down.
 */
class VirtualAccountSpecialTypeTest {

    private static VirtualAccount withCategory(AccountCategory category) {
        return VirtualAccount.builder().accountCategory(category).build();
    }

    /** A settlement account as it exists now: an ordinary transaction account carrying the mark. */
    private static VirtualAccount marked() {
        return VirtualAccount.builder()
                .accountCategory(AccountCategory.TRANSACTION)
                .settlementMark(true)
                .build();
    }

    @Test
    void theSettlementMarkDerivesSettlementSpecialType() {
        // Settlement moved off the category and onto its own mark, so the mark is what this reads.
        assertEquals(VaSpecialType.SETTLEMENT, marked().getSpecialType());
        // and the category alone no longer says it: a marked account's category is TRANSACTION
        assertEquals(AccountCategory.TRANSACTION, marked().getAccountCategory());
    }

    @Test
    void exceptionCategoryDerivesExceptionSpecialType() {
        assertEquals(VaSpecialType.EXCEPTION, withCategory(AccountCategory.EXCEPTION).getSpecialType());
    }

    @Test
    void everyOtherCategoryDerivesRegular() {
        for (AccountCategory category : AccountCategory.values()) {
            if (category == AccountCategory.EXCEPTION) {
                continue;
            }
            assertEquals(VaSpecialType.REGULAR, withCategory(category).getSpecialType(),
                    "category " + category + " should derive REGULAR");
        }
    }

    @Test
    void aNullCategoryDerivesRegularRatherThanThrowing() {
        assertEquals(VaSpecialType.REGULAR, withCategory(null).getSpecialType());
    }

    @Test
    void theTwoMarkersCanNoLongerDisagree() {
        // This is the defect the change exists to prevent: a VA that reads as settlement on one
        // field and not the other. specialType has no setter, so it cannot be set out of step --
        // and settlement now has exactly one home, the mark.
        for (AccountCategory category : AccountCategory.values()) {
            VirtualAccount va = withCategory(category);
            assertEquals(va.getSpecialType() == VaSpecialType.SETTLEMENT, va.isSettlementVa(),
                    "settlement marks disagree for " + category);
            assertEquals(va.getSpecialType() == VaSpecialType.EXCEPTION, va.isExceptionVa(),
                    "exception marks disagree for " + category);
        }
    }

    @Test
    void helpersStillClassifyTheSystemAccounts() {
        assertTrue(marked().isSystemVa(), "a marked settlement account is still a system VA");
        assertTrue(withCategory(AccountCategory.SUSPENSE).isSystemVa());
        assertFalse(withCategory(AccountCategory.TRANSACTION).isSystemVa());
        assertTrue(withCategory(AccountCategory.TRANSACTION).isRegularVa());
        assertFalse(marked().isRegularVa());
        // The category on its own carries no settlement meaning any more.
        assertFalse(withCategory(AccountCategory.SETTLEMENT).isSettlementVa(),
                "the bare category must not read as settlement -- the mark is the only source");
    }

    @Test
    void anUnmarkedTransactionAccountIsNotSettlement() {
        assertFalse(withCategory(AccountCategory.TRANSACTION).isSettlementVa());
        assertEquals(VaSpecialType.REGULAR, withCategory(AccountCategory.TRANSACTION).getSpecialType());
    }
}
