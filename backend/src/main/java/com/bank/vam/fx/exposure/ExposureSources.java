package com.bank.vam.fx.exposure;

import com.bank.vam.forecast.domain.enums.ForecastDirection;
import com.bank.vam.forecast.domain.enums.ForecastSource;
import com.bank.vam.forecast.domain.enums.RunStatus;
import com.bank.vam.fx.exposure.ExposureCalculator.Flow;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    /** The latest finished Cash Forecast run: when it ran, and the foreign-currency flows no invoice carries (signed: in positive, out negative). */
    record ForecastFlows(OffsetDateTime runAt, List<Flow> flows) {
    }

    /**
     * Categories that are not an exposure to hedge: intercompany nets inside the corporate, and an FX conversion is
     * itself a hedge, already counted from the deals.
     */
    private static final Set<String> NOT_EXPOSURE = Set.of("INTERCOMPANY_IN", "INTERCOMPANY_OUT", "FX_CONVERSION");

    /**
     * Forecast flows from the corporate's latest completed Cash Forecast run. AGING lines are left out: they project
     * the same open payables and receivables that {@link #payables} and {@link #receivables} read live, and a run can
     * be days or weeks old while invoices change daily. What remains is what no invoice carries: recurring flows
     * (payroll, rent, tax) from the pattern engine, model and driver lines, and manual entries. Past dates are dropped.
     * The forecast's adjustments are not applied, as the Cash Forecast summary does not apply them either.
     */
    Optional<ForecastFlows> forecast(UUID corporateId, String base, LocalDate today) {
        List<Object[]> run = em.createQuery("SELECT r.id, r.runAt FROM ForecastRun r WHERE r.corporateId = :c "
                        + "AND r.status = :s ORDER BY r.runAt DESC", Object[].class)
                .setParameter("c", corporateId).setParameter("s", RunStatus.COMPLETED).setMaxResults(1).getResultList();
        if (run.isEmpty()) {
            return Optional.empty();
        }
        List<Flow> flows = em.createQuery("SELECT l.currency, l.valueDate, l.amountMid, c.direction FROM ForecastLine l "
                        + "JOIN l.category c WHERE l.run.id = :run AND l.currency <> :base AND l.valueDate >= :today "
                        + "AND l.source <> :aging AND c.code NOT IN :excluded", Object[].class)
                .setParameter("run", run.get(0)[0]).setParameter("base", base).setParameter("today", today)
                .setParameter("aging", ForecastSource.AGING).setParameter("excluded", NOT_EXPOSURE)
                .getResultList().stream()
                .filter(r -> r[2] != null && ((BigDecimal) r[2]).signum() != 0)
                .map(r -> new Flow(((String) r[0]).trim(), (LocalDate) r[1],
                        r[3] == ForecastDirection.OUT ? ((BigDecimal) r[2]).abs().negate() : ((BigDecimal) r[2]).abs()))
                .toList();
        return Optional.of(new ForecastFlows((OffsetDateTime) run.get(0)[1], flows));
    }

    private List<Flow> flows(String jpql, UUID corporateId, String base) {
        return em.createQuery(jpql, Object[].class)
                .setParameter("c", corporateId).setParameter("base", base).getResultList().stream()
                .filter(r -> r[2] != null && ((BigDecimal) r[2]).signum() > 0)
                .map(r -> new Flow((String) r[0], (LocalDate) r[1], (BigDecimal) r[2]))
                .toList();
    }
}
