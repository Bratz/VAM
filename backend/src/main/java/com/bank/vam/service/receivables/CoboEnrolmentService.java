package com.bank.vam.service.receivables;

import com.bank.vam.config.CurrentUser;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.VirtualAccount.AccountCategory;
import com.bank.vam.entity.VirtualAccount.MirrorAccountType;
import com.bank.vam.entity.VirtualAccount.VaStatus;
import com.bank.vam.entity.hierarchy.LegalEntity;
import com.bank.vam.entity.receivables.CoboEnrolment;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.hierarchy.LegalEntityRepository;
import com.bank.vam.repository.receivables.CoboEnrolmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * COBO enrolment (spec docs/specs/cobo-in-house-bank.md R1-R3): a subsidiary, per currency, is collected on behalf
 * by the treasury centre into one of its collection accounts. Replaces the per-invoice request and approval.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CoboEnrolmentService {

    private final CoboEnrolmentRepository enrolments;
    private final LegalEntityRepository legalEntities;
    private final VirtualAccountRepository accounts;

    /** An enrolment with the names a screen or an invoice needs. */
    public record EnrolmentView(UUID id, UUID corporateId, UUID subsidiaryEntityId, String subsidiaryCode,
                                String subsidiaryName, String currency, UUID collectorEntityId, String collectorCode,
                                String collectorName, UUID collectionVaId, String collectionVaNumber,
                                String collectionVaName, UUID ihbAccountId, String ihbAccountNumber,
                                String ihbAccountCurrency, LocalDate effectiveFrom, LocalDate effectiveTo, boolean active,
                                String createdBy, LocalDateTime createdAt) {
    }

    /** An account the treasury centre can collect into. */
    public record CollectionAccount(UUID id, String vaNumber, String vaName, String currencyCode) {
    }

    @Transactional(readOnly = true)
    public List<EnrolmentView> list(UUID corporateId) {
        return enrolments.findByCorporateIdOrderByCreatedAtDesc(corporateId).stream().map(this::view).toList();
    }

    /** The enrolment that decides whether an invoice of {@code subsidiaryEntityId} in {@code currency} is COBO on {@code day}. */
    @Transactional(readOnly = true)
    public Optional<EnrolmentView> active(UUID subsidiaryEntityId, String currency, LocalDate day) {
        if (subsidiaryEntityId == null || currency == null) return Optional.empty();
        return enrolments.findBySubsidiaryEntityIdAndCurrency(subsidiaryEntityId, currency).stream()
            .filter(e -> e.isActiveOn(day)).findFirst().map(this::view);
    }

    @Transactional
    public EnrolmentView create(UUID corporateId, UUID subsidiaryEntityId, String currencyCode, UUID collectorEntityId,
                                UUID collectionVaId, UUID ihbAccountId, LocalDate effectiveFrom) {
        if (corporateId == null || subsidiaryEntityId == null || collectorEntityId == null || collectionVaId == null
            || currencyCode == null || currencyCode.isBlank()) {
            throw new BusinessException("Choose the corporate, subsidiary, currency, treasury centre and collection account");
        }
        String currency = currencyCode.trim().toUpperCase();
        LocalDate today = LocalDate.now();

        LegalEntity collector = entity(collectorEntityId, corporateId);
        if (!Boolean.TRUE.equals(collector.getIsTreasuryCenter())) {
            throw new BusinessException(collector.getEntityCode() + " is not a treasury centre: only an entity flagged as "
                + "the treasury centre can collect on behalf of others");
        }
        LegalEntity subsidiary = entity(subsidiaryEntityId, corporateId);
        if (subsidiary.getId().equals(collector.getId())) {
            throw new BusinessException(subsidiary.getEntityCode() + " cannot collect on its own behalf; choose a subsidiary");
        }

        // The accounts the in-house credit posts to (ReconciliationService.applyPayment): R3
        VirtualAccount ihbCurrent = settleAccount(subsidiary.getId(), ihbAccountId, currency)
            .orElseThrow(() -> new BusinessException(ihbAccountId != null
                ? "The account it settles on is not an active IHB current account of " + subsidiary.getEntityCode()
                : subsidiary.getEntityCode() + " has no active " + currency
                    + " IHB current account; open one on the In-House Bank page first"));
        // The collection account is in the settle currency, the IHB account's (ihb-fx R3)
        VirtualAccount collection = accounts.findById(collectionVaId)
            .orElseThrow(() -> new BusinessException("Collection account not found: " + collectionVaId));
        String problem = collectionAccountProblem(collection, collector.getId(), ihbCurrent.getCurrencyCode());
        if (problem != null) {
            throw new BusinessException("Collection account " + collection.getVaNumber() + " " + problem);
        }
        boolean icPayableActive = ihbCurrent.getIcPayableVaId() != null && accounts.findById(ihbCurrent.getIcPayableVaId())
            .map(va -> va.getStatus() == VaStatus.ACTIVE).orElse(false);
        if (!icPayableActive) {
            throw new BusinessException(subsidiary.getEntityCode() + "'s IHB current account " + ihbCurrent.getVaNumber()
                + " has no active IC payable at the treasury");
        }

        // ponytail: checked here, not by a DB constraint (ddl-auto can't make a partial unique index); a race of two
        // simultaneous enrolments could slip through, add a migration with the partial index if that ever matters.
        enrolments.findBySubsidiaryEntityIdAndCurrency(subsidiary.getId(), currency).stream()
            .filter(e -> e.isOpenOn(today)).findFirst().ifPresent(e -> {
                throw new BusinessException(subsidiary.getEntityCode() + " is already enrolled for " + currency
                    + "; end that enrolment first");
            });

        CoboEnrolment enrolment = CoboEnrolment.builder()
            .corporateId(corporateId)
            .subsidiaryEntityId(subsidiary.getId())
            .currency(currency)
            .collectorEntityId(collector.getId())
            .collectionVaId(collection.getId())
            .ihbAccountId(ihbAccountId)
            .effectiveFrom(effectiveFrom != null ? effectiveFrom : today)
            .build();
        enrolment.setCreatedBy(CurrentUser.name());
        CoboEnrolment saved = enrolments.save(enrolment);
        log.info("COBO enrolment: {} {} collected by {} into {}", subsidiary.getEntityCode(), currency,
            collector.getEntityCode(), collection.getVaNumber());
        return view(saved);
    }

    /** Ends the enrolment today: invoices created from now on are collected directly; existing ones are unchanged. */
    @Transactional
    public EnrolmentView end(UUID id) {
        CoboEnrolment e = enrolments.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("COBO enrolment not found: " + id));
        LocalDate today = LocalDate.now();
        if (!e.isOpenOn(today)) {
            throw new BusinessException("This enrolment already ended on " + e.getEffectiveTo());
        }
        e.setEffectiveTo(today);
        e.setUpdatedBy(CurrentUser.name());
        return view(enrolments.save(e));
    }

    /** The collector's accounts an enrolment settled in {@code currency} (its IHB account's) can collect into. */
    @Transactional(readOnly = true)
    public List<CollectionAccount> collectionAccounts(UUID collectorEntityId, String currency) {
        return accounts.findByOwningEntityIdAndStatus(collectorEntityId, VaStatus.ACTIVE).stream()
            .filter(va -> collectionAccountProblem(va, collectorEntityId, currency) == null)
            .map(va -> new CollectionAccount(va.getId(), va.getVaNumber(), va.getVaName(), va.getCurrencyCode()))
            .toList();
    }

    /** The IHB current account an enrolment settles on (ihb-fx D2): the subsidiary's active {@code ihbAccountId}, or when
     * null its active one in {@code currency}, as before D2. */
    private Optional<VirtualAccount> settleAccount(UUID subsidiaryId, UUID ihbAccountId, String currency) {
        return accounts.findByOwningEntityIdAndStatus(subsidiaryId, VaStatus.ACTIVE).stream()
            .filter(va -> va.getMirrorAccountType() == MirrorAccountType.IHB_CURRENT)
            .filter(va -> ihbAccountId != null ? ihbAccountId.equals(va.getId()) : currency.equals(va.getCurrencyCode()))
            .findFirst();
    }

    /** Why {@code va} can't be the collection account, or null. */
    private static String collectionAccountProblem(VirtualAccount va, UUID collectorEntityId, String currency) {
        if (!collectorEntityId.equals(va.getOwningEntityId())) return "is not the treasury centre's";
        if (!currency.equals(va.getCurrencyCode())) return "is in " + va.getCurrencyCode() + ", not " + currency;
        if (va.getStatus() != VaStatus.ACTIVE) return "is not active";
        if (va.isSettlementVa()) return "is a settlement account";
        if (va.getMirrorAccountType() != null && va.getMirrorAccountType() != MirrorAccountType.NONE) {
            return "is an in-house mirror account (" + va.getMirrorAccountType() + ")";
        }
        if (va.getAccountCategory() != AccountCategory.COLLECTION && va.getAccountCategory() != AccountCategory.TRANSACTION) {
            return "is a " + va.getAccountCategory() + " account, not one customers pay into";
        }
        if (va.getProgramId() == null) return "is in no program, so it cannot issue the invoices' VIBANs";
        return null;
    }

    private LegalEntity entity(UUID id, UUID corporateId) {
        LegalEntity e = legalEntities.findById(id).orElseThrow(() -> new BusinessException("Legal entity not found: " + id));
        if (!corporateId.equals(e.getCorporateId())) {
            throw new BusinessException(e.getEntityCode() + " belongs to another corporate");
        }
        return e;
    }

    private EnrolmentView view(CoboEnrolment e) {
        Optional<LegalEntity> sub = legalEntities.findById(e.getSubsidiaryEntityId());
        Optional<LegalEntity> col = legalEntities.findById(e.getCollectorEntityId());
        Optional<VirtualAccount> va = accounts.findById(e.getCollectionVaId());
        Optional<VirtualAccount> ihb = settleAccount(e.getSubsidiaryEntityId(), e.getIhbAccountId(), e.getCurrency());
        return new EnrolmentView(e.getId(), e.getCorporateId(),
            e.getSubsidiaryEntityId(), sub.map(LegalEntity::getEntityCode).orElse(null), sub.map(LegalEntity::getEntityName).orElse(null),
            e.getCurrency(),
            e.getCollectorEntityId(), col.map(LegalEntity::getEntityCode).orElse(null), col.map(LegalEntity::getEntityName).orElse(null),
            e.getCollectionVaId(), va.map(VirtualAccount::getVaNumber).orElse(null), va.map(VirtualAccount::getVaName).orElse(null),
            ihb.map(VirtualAccount::getId).orElse(null), ihb.map(VirtualAccount::getVaNumber).orElse(null),
            ihb.map(VirtualAccount::getCurrencyCode).orElse(null),
            e.getEffectiveFrom(), e.getEffectiveTo(), e.isActiveOn(LocalDate.now()), e.getCreatedBy(), e.getCreatedAt());
    }
}
