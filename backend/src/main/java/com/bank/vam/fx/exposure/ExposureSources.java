package com.bank.vam.fx.exposure;

import com.bank.vam.fx.exposure.ExposureCalculator.Flow;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reads the three places exposure already lives. Foreign currency only (anything in the base
 * currency is not an FX exposure), and intercompany items are left out: they net inside the
 * corporate. Queries are here rather than on the shared repositories so this feature adds nothing
 * to them.
 */
@Component
class ExposureSources {

    @PersistenceContext
    private EntityManager em;

    List<Flow> payables(UUID corporateId, String base) {
        return flows("SELECT p.currencyCode, p.dueDate, COALESCE(p.outstandingAmount, p.netAmount) FROM Payable p "
                + "WHERE p.corporateId = :c AND p.currencyCode <> :base "
                + "AND (p.isIntercompany IS NULL OR p.isIntercompany = false) "
                + "AND p.status NOT IN ('PAID','CANCELLED','NETTED','REJECTED','DRAFT')", corporateId, base);
    }

    List<Flow> receivables(UUID corporateId, String base) {
        return flows("SELECT r.currencyCode, r.dueDate, COALESCE(r.outstandingAmount, r.netAmount) FROM Receivable r "
                + "WHERE r.corporateId = :c AND r.currencyCode <> :base "
                + "AND (r.isIntercompany IS NULL OR r.isIntercompany = false) "
                + "AND r.status NOT IN ('PAID','CANCELLED','NETTED','WRITTEN_OFF','DRAFT','COBO_REJECTED')", corporateId, base);
    }

    /** Cash held at banks, taken from the shadows of the physical accounts so mirrors are not counted twice. */
    Map<String, BigDecimal> balances(UUID corporateId, String base) {
        List<Object[]> rows = em.createQuery(
                        "SELECT v.currencyCode, SUM(v.bankBalance) FROM VirtualAccount v "
                                + "WHERE v.corporateId = :c AND v.currencyCode <> :base "
                                + "AND v.accountCategory = 'PHYSICAL_MIRROR' AND v.status = 'ACTIVE' "
                                + "GROUP BY v.currencyCode", Object[].class)
                .setParameter("c", corporateId).setParameter("base", base).getResultList();
        Map<String, BigDecimal> out = new HashMap<>();
        for (Object[] r : rows) {
            if (r[1] != null) {
                out.put((String) r[0], (BigDecimal) r[1]);
            }
        }
        return out;
    }

    private List<Flow> flows(String jpql, UUID corporateId, String base) {
        return em.createQuery(jpql, Object[].class)
                .setParameter("c", corporateId).setParameter("base", base).getResultList().stream()
                .filter(r -> r[2] != null && ((BigDecimal) r[2]).signum() > 0)
                .map(r -> new Flow((String) r[0], (LocalDate) r[1], (BigDecimal) r[2]))
                .toList();
    }
}
