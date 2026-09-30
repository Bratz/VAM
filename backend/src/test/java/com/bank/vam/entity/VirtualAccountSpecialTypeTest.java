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

    @Test
    void settlementCategoryDerivesSettlementSpecialType() {
        assertEquals(VaSpecialType.SETTLEMENT, withCategory(AccountCategory.SETTLEMENT).getSpecialType());
    }

    @Test
    void exceptionCategoryDerivesExceptionSpecialType() {
        assertEquals(VaSpecialType.EXCEPTION, withCategory(AccountCategory.EXCEPTION).getSpecialType());
    }

    @Test
    void everyOtherCategoryDerivesRegular() {
        for (AccountCategory category : AccountCategory.values()) {
            if (category == AccountCategory.SETTLEMENT || category == AccountCategory.EXCEPTION) {
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
        // field and not the other. There is no setter any more, so the only way in is the category.
        for (AccountCategory category : AccountCategory.values()) {
            VirtualAccount va = withCategory(category);
            assertEquals(va.getSpecialType() == VaSpecialType.SETTLEMENT, va.isSettlementVa(),
                    "settlement marks disagree for " + category);
            assertEquals(va.getSpecialType() == VaSpecialType.EXCEPTION, va.isExceptionVa(),
                    "exception marks disagree for " + category);
        }
    }

    @Test
    void helpersStillClassifyTheSystemCategories() {
        assertTrue(withCategory(AccountCategory.SETTLEMENT).isSystemVa());
        assertTrue(withCategory(AccountCategory.SUSPENSE).isSystemVa());
        assertFalse(withCategory(AccountCategory.TRANSACTION).isSystemVa());
        assertTrue(withCategory(AccountCategory.TRANSACTION).isRegularVa());
        assertFalse(withCategory(AccountCategory.SETTLEMENT).isRegularVa());
    }
}
