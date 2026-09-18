package com.bank.vam.entity.payables;

import com.bank.vam.entity.payables.Payable.NettingStatus;
import com.bank.vam.entity.payables.Payable.PayableStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for canAddToNetting() excluding payables with incomplete
 * owning-entity data, which previously violated the netting_entries NOT NULL
 * constraint on payer_entity_id/payer_entity_code at insert time.
 */
class PayableTest {

    private Payable eligiblePayable() {
        return Payable.builder()
            .status(PayableStatus.APPROVED)
            .isIntercompany(true)
            .nettingStatus(NettingStatus.NOT_INCLUDED)
            .owningEntityId(UUID.randomUUID())
            .owningEntityCode("SUB-DUBAI")
            .netAmount(BigDecimal.TEN)
            .build();
    }

    @Test
    void canAddToNetting_trueWhenOwningEntityFullyPopulated() {
        assertTrue(eligiblePayable().canAddToNetting());
    }

    @Test
    void canAddToNetting_falseWhenOwningEntityCodeMissing() {
        Payable payable = eligiblePayable();
        payable.setOwningEntityCode(null);
        assertFalse(payable.canAddToNetting());
    }

    @Test
    void canAddToNetting_falseWhenOwningEntityIdMissing() {
        Payable payable = eligiblePayable();
        payable.setOwningEntityId(null);
        assertFalse(payable.canAddToNetting());
    }
}
