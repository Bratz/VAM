package com.bank.vam.service.treasury;

import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.hierarchy.LegalEntity;
import com.bank.vam.repository.VirtualAccountRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * An entity's use of its IHB credit limit, from the money: what it has drawn is the debit balance of its active IHB
 * current accounts in its IHB currency (the sum of max(0, -balance)). The stored LegalEntity exposure it replaces was
 * never maintained after IHB loans were retired (V58 drops it). Every screen and check reads this one figure.
 * ponytail: accounts in other currencies are left out (the limit is in the IHB currency); convert at the FX rate if
 * entities start drawing in several currencies.
 */
public record IhbExposure(BigDecimal creditLimit, BigDecimal drawn) {

    public static IhbExposure of(VirtualAccountRepository accounts, LegalEntity entity) {
        String ccy = entity.getEffectiveIhbCurrency();
        BigDecimal drawn = entity.getId() == null || ccy == null ? BigDecimal.ZERO
                : accounts.findByOwningEntityId(entity.getId()).stream()
                    .filter(va -> va.isIhbParticipant() && va.isActive() && ccy.equals(va.getCurrencyCode()))
                    .map(IhbExposure::debit)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new IhbExposure(entity.getIhbCreditLimit() != null ? entity.getIhbCreditLimit() : BigDecimal.ZERO, drawn);
    }

    private static BigDecimal debit(VirtualAccount va) {
        return va.getCurrentBalance() != null ? va.getCurrentBalance().negate().max(BigDecimal.ZERO) : BigDecimal.ZERO;
    }

    public BigDecimal available() {
        return creditLimit.subtract(drawn).max(BigDecimal.ZERO);
    }

    public BigDecimal utilisationPercent() {
        return creditLimit.signum() > 0
                ? drawn.multiply(BigDecimal.valueOf(100)).divide(creditLimit, 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
    }

    public boolean warning() {
        return utilisationPercent().compareTo(BigDecimal.valueOf(80)) >= 0;
    }

    public boolean breached() {
        return utilisationPercent().compareTo(BigDecimal.valueOf(100)) >= 0;
    }
}
