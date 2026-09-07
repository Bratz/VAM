package com.bank.vam.entity;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link VirtualAccount#applyShadowMovement} and
 * {@link VirtualAccount#mirrorBankBalance} — the CBS-mirroring primitive shared by
 * TransactionService's payment legs, IhbSettlementService's net settlement, and
 * SweepService's deficit-funding transfer. Regression guard for the shadow-account
 * sweep fix: a shadow's real {@code bankBalance} must move in lockstep with its
 * ledger balance, and must never move for a plain operational VA.
 */
class VirtualAccountShadowMovementTest {

    private VirtualAccount shadowVa(BigDecimal currentBalance, BigDecimal bankBalance) {
        VirtualAccount va = new VirtualAccount();
        va.setAccountCategory(VirtualAccount.AccountCategory.PHYSICAL_MIRROR);
        va.setCurrentBalance(currentBalance);
        va.setAvailableBalance(currentBalance);
        va.setBankBalance(bankBalance);
        return va;
    }

    @Test
    void applyShadowMovement_debitsLedgerAndMirrorsBankBalance() {
        VirtualAccount shadow = shadowVa(new BigDecimal("1000.00"), new BigDecimal("5000.00"));

        shadow.applyShadowMovement(new BigDecimal("-200.00"));

        assertThat(shadow.getCurrentBalance()).isEqualByComparingTo("800.00");
        assertThat(shadow.getAvailableBalance()).isEqualByComparingTo("800.00");
        assertThat(shadow.getBankBalance()).isEqualByComparingTo("4800.00");
        assertThat(shadow.getBankBalanceAt()).isNotNull();
    }

    @Test
    void applyShadowMovement_creditsLedgerAndMirrorsBankBalance() {
        VirtualAccount shadow = shadowVa(new BigDecimal("1000.00"), new BigDecimal("5000.00"));

        shadow.applyShadowMovement(new BigDecimal("300.00"));

        assertThat(shadow.getCurrentBalance()).isEqualByComparingTo("1300.00");
        assertThat(shadow.getBankBalance()).isEqualByComparingTo("5300.00");
    }

    @Test
    void mirrorBankBalance_isNoOpWhenAccountHasNoBankBalance() {
        VirtualAccount operational = new VirtualAccount();
        operational.setAccountCategory(VirtualAccount.AccountCategory.TRANSACTION);
        operational.setCurrentBalance(new BigDecimal("1000.00"));
        // bankBalance left null, as it always is for a non-mirror operational VA.

        operational.mirrorBankBalance(new BigDecimal("-200.00"));

        assertThat(operational.getBankBalance()).isNull();
        assertThat(operational.getBankBalanceAt()).isNull();
    }

    @Test
    void mirrorBankBalance_appliesDeltaWhenBankBalancePresent() {
        VirtualAccount shadow = shadowVa(new BigDecimal("1000.00"), new BigDecimal("5000.00"));

        shadow.mirrorBankBalance(new BigDecimal("-750.00"));

        // Only bankBalance moves here — mirrorBankBalance alone never touches the ledger
        // fields, since callers (e.g. IhbSettlementService.executeNetSettlement) already
        // moved currentBalance/availableBalance via settleOutflow/credit/debit/settleInflow.
        assertThat(shadow.getCurrentBalance()).isEqualByComparingTo("1000.00");
        assertThat(shadow.getBankBalance()).isEqualByComparingTo("4250.00");
    }
}
