package com.bank.vam.fx.deal;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Cash a corporate holds in one currency, from the shadows of its physical bank accounts (so mirrors are
 * not counted twice). Settlement posts to those mirrors, so it moves this figure.
 */
@Component
class HeldCash {

    @PersistenceContext
    private EntityManager em;

    BigDecimal of(UUID corporateId, String currency) {
        BigDecimal sum = em.createQuery(
                        "SELECT COALESCE(SUM(v.bankBalance), 0) FROM VirtualAccount v WHERE v.corporateId = :c "
                                + "AND v.currencyCode = :ccy AND v.accountCategory = 'PHYSICAL_MIRROR' AND v.status = 'ACTIVE'",
                        BigDecimal.class)
                .setParameter("c", corporateId).setParameter("ccy", currency).getSingleResult();
        return sum == null ? BigDecimal.ZERO : sum;
    }
}
