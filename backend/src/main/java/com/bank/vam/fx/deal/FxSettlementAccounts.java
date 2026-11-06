package com.bank.vam.fx.deal;

import com.bank.vam.config.MarketProfileProperties;
import com.bank.vam.entity.hierarchy.LegalEntity;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.hierarchy.LegalEntityRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Which account an entity's FX deals settle through in a currency. Eligible: the entity's active bank-account
 * mirrors at the home bank in that currency (the bank deals, so it can debit and credit them). The entity's
 * standing settlement instruction wins; without one, its only eligible account is the default; with none, or
 * several and no instruction, there is no account and the reason says why.
 */
@Service
@RequiredArgsConstructor
public class FxSettlementAccounts {

    /** An eligible account. {@code label} is what screens and the confirmation show. */
    public record Account(UUID id, String currency, String label, String bankName, BigDecimal balance) {
    }

    /** The account for one currency, or why there is none. {@code fromInstruction} is false for a derived default. */
    public record Resolved(String currency, Account account, boolean fromInstruction, String reason) {
        public boolean found() {
            return account != null;
        }
    }

    /** One entity's instruction for one currency, as the Settlement Instructions page shows it. */
    public record Row(UUID entityId, String entityName, String currency, Resolved resolved, List<Account> eligible) {
    }

    @PersistenceContext
    private EntityManager em;

    private final FxSettlementInstructionRepository instructions;
    private final LegalEntityRepository entities;
    private final MarketProfileProperties marketProfile;

    /** The entity's home-bank accounts in the currency, oldest first. */
    public List<Account> eligible(UUID entityId, String currency) {
        String bic = homeBic();
        if (bic == null) {
            return List.of();
        }
        List<Object[]> rows = em.createQuery("SELECT v.id, v.currencyCode, p.iban, p.accountNumber, p.bankName, v.bankBalance, v.vaName "
                        + "FROM VirtualAccount v, PhysicalAccount p WHERE p.id = v.physicalAccountId "
                        + "AND v.owningEntityId = :entity AND v.currencyCode = :ccy AND v.accountCategory = 'PHYSICAL_MIRROR' "
                        + "AND v.status = 'ACTIVE' AND UPPER(SUBSTRING(p.bankCode, 1, 8)) = :bic ORDER BY v.createdAt", Object[].class)
                .setParameter("entity", entityId).setParameter("ccy", currency).setParameter("bic", bic)
                .getResultList();
        return rows.stream().map(r -> {
            String ref = r[2] != null ? (String) r[2] : r[3] != null ? (String) r[3] : (String) r[6];
            String bank = r[4] == null ? "home bank" : (String) r[4];
            BigDecimal balance = r[5] == null ? BigDecimal.ZERO : (BigDecimal) r[5];
            return new Account((UUID) r[0], (String) r[1], ref + " (" + bank + ")", bank, balance);
        }).toList();
    }

    public Resolved resolve(UUID corporateId, UUID entityId, String currency) {
        LegalEntity e = entityOf(corporateId, entityId);
        List<Account> eligible = eligible(entityId, currency);
        Optional<FxSettlementInstruction> ssi = instructions.findByEntityIdAndCurrency(entityId, currency);
        if (ssi.isPresent()) {
            return eligible.stream().filter(a -> a.id().equals(ssi.get().getAccountId())).findFirst()
                    .map(a -> new Resolved(currency, a, true, null))
                    .orElse(new Resolved(currency, null, true, "The " + currency + " settlement instruction of " + e.getEntityName()
                            + " points to an account that is no longer an active home-bank account; change it under Settlement Instructions"));
        }
        if (eligible.size() == 1) {
            return new Resolved(currency, eligible.get(0), false, null);
        }
        if (eligible.isEmpty()) {
            return new Resolved(currency, null, false, e.getEntityName() + " has no " + currency
                    + " account at the home bank to settle in; the bank opens one, then it is used automatically");
        }
        return new Resolved(currency, null, false, e.getEntityName() + " has " + eligible.size() + " " + currency
                + " accounts at the home bank; choose one under Settlement Instructions");
    }

    /** The account must be one the entity may settle through in that currency, else this throws. */
    public Account require(UUID corporateId, UUID entityId, String currency, UUID accountId) {
        LegalEntity e = entityOf(corporateId, entityId);
        return eligible(entityId, currency).stream().filter(a -> a.id().equals(accountId)).findFirst()
                .orElseThrow(() -> new BusinessException("FX_SETTLEMENT_ACCOUNT_INVALID", "That account is not one of "
                        + e.getEntityName() + "'s active " + currency + " accounts at the home bank"));
    }

    @Transactional
    public Row setInstruction(UUID corporateId, UUID entityId, String currency, UUID accountId) {
        String ccy = currency.toUpperCase(Locale.ROOT);
        require(corporateId, entityId, ccy, accountId);
        FxSettlementInstruction ssi = instructions.findByEntityIdAndCurrency(entityId, ccy).orElseGet(FxSettlementInstruction::new);
        ssi.setCorporateId(corporateId);
        ssi.setEntityId(entityId);
        ssi.setCurrency(ccy);
        ssi.setAccountId(accountId);
        instructions.save(ssi);
        LegalEntity e = entityOf(corporateId, entityId);
        return new Row(entityId, e.getEntityName(), ccy, resolve(corporateId, entityId, ccy), eligible(entityId, ccy));
    }

    /**
     * Every active entity of the corporate, for every currency it holds a home-bank account in or has an instruction
     * for: what its deals settle through, or why they cannot.
     */
    public List<Row> rows(UUID corporateId) {
        List<FxSettlementInstruction> all = instructions.findByCorporateId(corporateId);
        return entities.findByCorporateIdAndStatus(corporateId, LegalEntity.EntityStatus.ACTIVE).stream()
                .sorted(Comparator.comparing(LegalEntity::getEntityName))
                .flatMap(e -> {
                    TreeSet<String> currencies = new TreeSet<>(homeBankCurrencies(e.getId()));
                    all.stream().filter(i -> i.getEntityId().equals(e.getId())).forEach(i -> currencies.add(i.getCurrency()));
                    return currencies.stream().map(c -> new Row(e.getId(), e.getEntityName(), c, resolve(corporateId, e.getId(), c),
                            eligible(e.getId(), c)));
                })
                .toList();
    }

    private List<String> homeBankCurrencies(UUID entityId) {
        String bic = homeBic();
        if (bic == null) {
            return List.of();
        }
        return em.createQuery("SELECT DISTINCT v.currencyCode FROM VirtualAccount v, PhysicalAccount p WHERE p.id = v.physicalAccountId "
                        + "AND v.owningEntityId = :entity AND v.accountCategory = 'PHYSICAL_MIRROR' AND v.status = 'ACTIVE' "
                        + "AND UPPER(SUBSTRING(p.bankCode, 1, 8)) = :bic", String.class)
                .setParameter("entity", entityId).setParameter("bic", bic).getResultList();
    }

    /** The first eight characters of the home bank's BIC: an 8- and an 11-character BIC name the same bank. */
    private String homeBic() {
        String bic = marketProfile.getActiveHomeBankBic();
        return bic == null || bic.length() < 8 ? null : bic.substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private LegalEntity entityOf(UUID corporateId, UUID entityId) {
        if (entityId == null) {
            throw new BusinessException("FX_REQUEST_INVALID", "Choose the legal entity the deal is for");
        }
        LegalEntity e = entities.findById(entityId).orElseThrow(() -> new ResourceNotFoundException("Legal entity not found: " + entityId));
        if (!corporateId.equals(e.getCorporateId())) {
            throw new BusinessException("FX_ENTITY_INVALID", e.getEntityName() + " is not an entity of this corporate");
        }
        return e;
    }
}
