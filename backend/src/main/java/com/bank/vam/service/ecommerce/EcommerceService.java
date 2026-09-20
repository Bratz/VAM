package com.bank.vam.service.ecommerce;

import com.bank.vam.exception.ResourceNotFoundException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * E-commerce collections, read from what the platform actually records: money
 * arriving on the virtual accounts of COLLECTION-type programs, and the sweeps
 * that move it on.
 *
 * A "collection" here is a credit movement on a collection VA — there is no
 * separate collections table, and this does not invent one. The merchant
 * columns (merchant_id, merchant_name, merchant_category, terminal_id,
 * authorization_code) exist on va_movements but nothing populates them today,
 * so the merchant endpoints group over those columns and correctly return
 * nothing until an acquiring feed starts filling them in.
 *
 * ponytail: native SQL rather than JPQL — every query here is an aggregate
 * across movement → VA → program, which reads far better in SQL than in
 * criteria form.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EcommerceService {

    /** Credits into a collection-program VA: the money a corporate has collected. */
    private static final String COLLECTION_CREDITS =
        " FROM va_movements m " +
        " JOIN virtual_accounts v ON v.id = m.va_id " +
        " JOIN unified_programs p ON p.id = v.program_id " +
        " WHERE p.program_type = 'COLLECTION' AND m.movement_type = 'CREDIT' ";

    @PersistenceContext
    private EntityManager em;

    @Transactional(readOnly = true)
    public DashboardStatsResponse getDashboardStats(UUID corporateId) {
        String corpFilter = corporateId != null ? " AND v.corporate_id = :corporateId " : "";

        Object[] totals = (Object[]) q(
            "SELECT COUNT(*), COALESCE(SUM(m.amount),0), COUNT(DISTINCT v.id), " +
            "       COUNT(*) FILTER (WHERE m.status = 'COMPLETED') " +
            COLLECTION_CREDITS + corpFilter, corporateId).getSingleResult();

        Object[] today = (Object[]) q(
            "SELECT COUNT(*), COALESCE(SUM(m.amount),0) " +
            COLLECTION_CREDITS + corpFilter +
            " AND m.transaction_date >= :dayStart ", corporateId)
            .setParameter("dayStart", LocalDate.now().atStartOfDay())
            .getSingleResult();

        // Money still sitting on collection accounts — not yet swept onward.
        Object[] held = (Object[]) q(
            "SELECT COUNT(*), COALESCE(SUM(v.current_balance),0) FROM virtual_accounts v " +
            " JOIN unified_programs p ON p.id = v.program_id " +
            " WHERE p.program_type = 'COLLECTION' " +
            (corporateId != null ? " AND v.corporate_id = :corporateId " : ""), corporateId)
            .getSingleResult();

        long count = num(totals[0]).longValue();
        BigDecimal volume = dec(totals[1]);
        long completed = num(totals[3]).longValue();

        return DashboardStatsResponse.builder()
            .collectionAccounts(num(totals[2]).longValue())
            .totalCollections(volume)
            .totalTransactions(count)
            .todayCollections(dec(today[1]))
            .transactionsToday(num(today[0]).longValue())
            .heldOnCollectionAccounts(dec(held[1]))
            .averageTicketSize(count > 0 ? volume.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP) : BigDecimal.ZERO)
            .successRate(count > 0 ? BigDecimal.valueOf(completed * 100.0 / count).setScale(2, RoundingMode.HALF_UP) : null)
            .build();
    }

    @Transactional(readOnly = true)
    public List<TrendPoint> getCollectionTrends(UUID corporateId, int days) {
        String corpFilter = corporateId != null ? " AND v.corporate_id = :corporateId " : "";
        @SuppressWarnings("unchecked")
        List<Object[]> rows = q(
            "SELECT CAST(m.transaction_date AS date) d, COUNT(*), COALESCE(SUM(m.amount),0) " +
            COLLECTION_CREDITS + corpFilter +
            " AND m.transaction_date >= :from GROUP BY d ORDER BY d ", corporateId)
            .setParameter("from", LocalDate.now().minusDays(days).atStartOfDay())
            .getResultList();

        List<TrendPoint> out = new ArrayList<>();
        for (Object[] r : rows) {
            out.add(TrendPoint.builder()
                .date(((java.sql.Date) r[0]).toLocalDate())
                .transactions(num(r[1]).longValue())
                .collections(dec(r[2]))
                .build());
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<CollectionResponse> getCollections(UUID corporateId, UUID vaId, String status, int page, int size) {
        StringBuilder sql = new StringBuilder(
            "SELECT m.id, m.reference_number, m.amount, m.currency_code, m.status, " +
            "       m.remitter_name, m.remitter_account, m.description, m.transaction_date, " +
            "       m.channel, m.external_reference, m.viban, v.id, v.va_number, v.va_name, p.program_name " +
            COLLECTION_CREDITS);
        if (corporateId != null) sql.append(" AND v.corporate_id = :corporateId ");
        if (vaId != null) sql.append(" AND v.id = :vaId ");
        if (status != null && !status.isBlank()) sql.append(" AND m.status = :status ");
        sql.append(" ORDER BY m.transaction_date DESC ");

        Query query = q(sql.toString(), corporateId);
        if (vaId != null) query.setParameter("vaId", vaId);
        if (status != null && !status.isBlank()) query.setParameter("status", status.toUpperCase());

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query
            .setFirstResult(Math.max(0, page) * Math.max(1, size))
            .setMaxResults(Math.max(1, size))
            .getResultList();

        List<CollectionResponse> out = new ArrayList<>();
        for (Object[] r : rows) out.add(toCollection(r));
        return out;
    }

    @Transactional(readOnly = true)
    public CollectionResponse getCollection(UUID movementId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = q(
            "SELECT m.id, m.reference_number, m.amount, m.currency_code, m.status, " +
            "       m.remitter_name, m.remitter_account, m.description, m.transaction_date, " +
            "       m.channel, m.external_reference, m.viban, v.id, v.va_number, v.va_name, p.program_name " +
            COLLECTION_CREDITS + " AND m.id = :id ", null)
            .setParameter("id", movementId)
            .getResultList();
        if (rows.isEmpty()) throw new ResourceNotFoundException("Collection not found: " + movementId);
        return toCollection(rows.get(0));
    }

    /**
     * Collection accounts — the virtual accounts money is actually collected
     * into, with their real credited volume. This is the platform's equivalent
     * of a merchant list; see {@link #getMerchants} for the acquiring view.
     */
    @Transactional(readOnly = true)
    public List<CollectionAccountResponse> getCollectionAccounts(UUID corporateId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = q(
            "SELECT v.id, v.va_number, v.va_name, v.currency_code, v.current_balance, v.status, " +
            "       p.program_name, COUNT(m.id), COALESCE(SUM(m.amount),0), MAX(m.transaction_date) " +
            " FROM virtual_accounts v " +
            " JOIN unified_programs p ON p.id = v.program_id " +
            " LEFT JOIN va_movements m ON m.va_id = v.id AND m.movement_type = 'CREDIT' " +
            " WHERE p.program_type = 'COLLECTION' " +
            (corporateId != null ? " AND v.corporate_id = :corporateId " : "") +
            " GROUP BY v.id, v.va_number, v.va_name, v.currency_code, v.current_balance, v.status, p.program_name " +
            " ORDER BY COALESCE(SUM(m.amount),0) DESC ", corporateId)
            .getResultList();

        List<CollectionAccountResponse> out = new ArrayList<>();
        for (Object[] r : rows) {
            out.add(CollectionAccountResponse.builder()
                .vaId((UUID) r[0])
                .vaNumber((String) r[1])
                .vaName((String) r[2])
                .currencyCode((String) r[3])
                .currentBalance(dec(r[4]))
                .status(str(r[5]))
                .programName((String) r[6])
                .collectionCount(num(r[7]).longValue())
                .collectedVolume(dec(r[8]))
                .lastCollectionAt(time(r[9]))
                .build());
        }
        return out;
    }

    /**
     * Acquiring merchants, grouped from the merchant columns on va_movements.
     * Those columns are part of the schema but no feed populates them yet, so
     * this returns an empty list rather than a fabricated roster — and starts
     * returning real rows the moment merchant data is written.
     */
    @Transactional(readOnly = true)
    public List<MerchantResponse> getMerchants(UUID corporateId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = q(
            "SELECT m.merchant_id, MAX(m.merchant_name), MAX(m.merchant_category), " +
            "       COUNT(*), COALESCE(SUM(m.amount),0), MAX(m.transaction_date), " +
            "       COUNT(DISTINCT m.terminal_id) " +
            " FROM va_movements m " +
            " WHERE m.merchant_id IS NOT NULL " +
            (corporateId != null ? " AND m.corporate_id = :corporateId " : "") +
            " GROUP BY m.merchant_id ORDER BY COALESCE(SUM(m.amount),0) DESC ", corporateId)
            .getResultList();

        List<MerchantResponse> out = new ArrayList<>();
        for (Object[] r : rows) {
            out.add(MerchantResponse.builder()
                .merchantId((String) r[0])
                .merchantName((String) r[1])
                .category((String) r[2])
                .transactionCount(num(r[3]).longValue())
                .volume(dec(r[4]))
                .lastTransactionAt(time(r[5]))
                .terminalCount(num(r[6]).longValue())
                .build());
        }
        return out;
    }

    /**
     * Settlement runs — the sweeps that move collected money off the collection
     * accounts, grouped by value date. These are real SWEEP_OUT movements, not a
     * separate settlement ledger.
     */
    @Transactional(readOnly = true)
    public List<SettlementResponse> getSettlements(UUID corporateId, int limit) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = q(
            "SELECT CAST(m.transaction_date AS date) d, m.currency_code, COUNT(*), " +
            "       COALESCE(SUM(m.amount),0), COALESCE(SUM(m.fee_amount),0), COUNT(DISTINCT v.id) " +
            " FROM va_movements m " +
            " JOIN virtual_accounts v ON v.id = m.va_id " +
            " JOIN unified_programs p ON p.id = v.program_id " +
            " WHERE p.program_type = 'COLLECTION' AND m.movement_type = 'SWEEP_OUT' " +
            (corporateId != null ? " AND v.corporate_id = :corporateId " : "") +
            " GROUP BY d, m.currency_code ORDER BY d DESC ", corporateId)
            .setMaxResults(Math.max(1, limit))
            .getResultList();

        List<SettlementResponse> out = new ArrayList<>();
        for (Object[] r : rows) {
            BigDecimal gross = dec(r[3]);
            BigDecimal fees = dec(r[4]);
            out.add(SettlementResponse.builder()
                .settlementDate(((java.sql.Date) r[0]).toLocalDate())
                .currencyCode((String) r[1])
                .transactionCount(num(r[2]).longValue())
                .grossAmount(gross)
                .fees(fees)
                .netAmount(gross.subtract(fees))
                .accountsSwept(num(r[5]).longValue())
                .build());
        }
        return out;
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private Query q(String sql, UUID corporateId) {
        Query query = em.createNativeQuery(sql);
        if (corporateId != null && sql.contains(":corporateId")) {
            query.setParameter("corporateId", corporateId);
        }
        return query;
    }

    private CollectionResponse toCollection(Object[] r) {
        return CollectionResponse.builder()
            .id((UUID) r[0])
            .reference((String) r[1])
            .amount(dec(r[2]))
            .currencyCode((String) r[3])
            .status(str(r[4]))
            .remitterName((String) r[5])
            .remitterAccount((String) r[6])
            .description((String) r[7])
            .transactionDate(time(r[8]))
            .channel((String) r[9])
            .externalReference((String) r[10])
            .viban((String) r[11])
            .vaId((UUID) r[12])
            .vaNumber((String) r[13])
            .vaName((String) r[14])
            .programName((String) r[15])
            .build();
    }

    private static Number num(Object o) { return o == null ? 0 : (Number) o; }
    private static BigDecimal dec(Object o) { return o == null ? BigDecimal.ZERO : new BigDecimal(o.toString()); }
    private static String str(Object o) { return o == null ? null : o.toString(); }
    private static LocalDateTime time(Object o) {
        if (o == null) return null;
        return o instanceof Timestamp ts ? ts.toLocalDateTime() : (LocalDateTime) o;
    }

    // ------------------------------------------------------------------
    // DTOs
    // ------------------------------------------------------------------

    @lombok.Data @lombok.Builder @lombok.NoArgsConstructor @lombok.AllArgsConstructor
    public static class DashboardStatsResponse {
        private long collectionAccounts;
        private BigDecimal totalCollections;
        private long totalTransactions;
        private BigDecimal todayCollections;
        private long transactionsToday;
        /** Balance still on the collection accounts, not yet swept onward. */
        private BigDecimal heldOnCollectionAccounts;
        private BigDecimal averageTicketSize;
        /** Share of collection credits in COMPLETED status; null when there are none. */
        private BigDecimal successRate;
    }

    @lombok.Data @lombok.Builder @lombok.NoArgsConstructor @lombok.AllArgsConstructor
    public static class TrendPoint {
        private LocalDate date;
        private long transactions;
        private BigDecimal collections;
    }

    @lombok.Data @lombok.Builder @lombok.NoArgsConstructor @lombok.AllArgsConstructor
    public static class CollectionResponse {
        private UUID id;
        private String reference;
        private BigDecimal amount;
        private String currencyCode;
        private String status;
        private String remitterName;
        private String remitterAccount;
        private String description;
        private LocalDateTime transactionDate;
        private String channel;
        private String externalReference;
        private String viban;
        private UUID vaId;
        private String vaNumber;
        private String vaName;
        private String programName;
    }

    @lombok.Data @lombok.Builder @lombok.NoArgsConstructor @lombok.AllArgsConstructor
    public static class CollectionAccountResponse {
        private UUID vaId;
        private String vaNumber;
        private String vaName;
        private String currencyCode;
        private BigDecimal currentBalance;
        private String status;
        private String programName;
        private long collectionCount;
        private BigDecimal collectedVolume;
        private LocalDateTime lastCollectionAt;
    }

    @lombok.Data @lombok.Builder @lombok.NoArgsConstructor @lombok.AllArgsConstructor
    public static class MerchantResponse {
        private String merchantId;
        private String merchantName;
        private String category;
        private long transactionCount;
        private BigDecimal volume;
        private LocalDateTime lastTransactionAt;
        private long terminalCount;
    }

    @lombok.Data @lombok.Builder @lombok.NoArgsConstructor @lombok.AllArgsConstructor
    public static class SettlementResponse {
        private LocalDate settlementDate;
        private String currencyCode;
        private long transactionCount;
        private long accountsSwept;
        private BigDecimal grossAmount;
        private BigDecimal fees;
        private BigDecimal netAmount;
    }
}
