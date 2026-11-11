package com.bank.vam.service.pobo;

import com.bank.vam.config.CurrentUser;
import com.bank.vam.entity.PhysicalAccount;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.VirtualAccount.AccountCategory;
import com.bank.vam.entity.VirtualAccount.MirrorAccountType;
import com.bank.vam.entity.VirtualAccount.VaStatus;
import com.bank.vam.entity.hierarchy.LegalEntity;
import com.bank.vam.entity.payables.Payable;
import com.bank.vam.entity.pobo.PoboEnrolment;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.fx.deal.FxPaymentService;
import com.bank.vam.repository.PhysicalAccountRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.hierarchy.LegalEntityRepository;
import com.bank.vam.repository.payables.PayableRepository;
import com.bank.vam.repository.pobo.PoboEnrolmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Stream;

/**
 * POBO enrolment (spec docs/specs/pobo-in-house-bank.md R1-R2, D1/D2/D7): a subsidiary, per currency, is paid for by
 * the treasury centre from one of its bank-backed accounts, booked against the subsidiary's IHB current account.
 * Replaces Request POBO and the treasury approval; the enrolment on the payment date decides the route.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PoboEnrolmentService {

    private final PoboEnrolmentRepository enrolments;
    private final LegalEntityRepository legalEntities;
    private final VirtualAccountRepository accounts;
    private final PhysicalAccountRepository physicalAccounts;
    private final PayableRepository payables;

    /** An enrolment with the names a screen or a payable needs; {@code headroom} = IHB current balance + credit limit. */
    public record EnrolmentView(UUID id, UUID corporateId, UUID subsidiaryEntityId, String subsidiaryCode,
                                String subsidiaryName, String currency, UUID payerEntityId, String payerCode,
                                String payerName, UUID paymentVaId, String paymentVaNumber, String paymentVaName,
                                String paymentIban, UUID ihbCurrentVaId, String ihbCurrentVaNumber, String ihbCurrentCurrency,
                                BigDecimal headroom,
                                LocalDate effectiveFrom, LocalDate effectiveTo, boolean active, String createdBy,
                                LocalDateTime createdAt) {
    }

    /** The debtor side of a pain.001 paid on behalf (D6); {@code bic} null means the home bank's. */
    public record PainDebtor(String name, String code, String iban, String bic, String ultimateDebtor) {
    }

    /** An account the treasury centre can pay from. */
    public record PaymentAccount(UUID id, String vaNumber, String vaName, String currencyCode, String iban) {
    }

    /** How a payment of an enrolled subsidiary runs: from {@code ihbCurrent} (the subsidiary's side) by {@code paymentVa}. */
    public record Route(PoboEnrolment enrolment, LegalEntity subsidiary, LegalEntity payer, VirtualAccount paymentVa,
                        VirtualAccount ihbCurrent) {
        public BigDecimal headroom() {
            return PoboEnrolmentService.headroom(ihbCurrent);
        }

        /** Paid in the enrolment currency, settled on an IHB account in another (ihb-fx D2). */
        public boolean crossCurrency() {
            return enrolment.getCurrency() != null && !enrolment.getCurrency().equals(ihbCurrent.getCurrencyCode());
        }

        /** Why a payment settling {@code settleAmount} (in the IHB account's currency) is refused before money moves, or null. */
        public String refusal(BigDecimal settleAmount) {
            return headroomRefusal(ihbCurrent, settleAmount);
        }

        /**
         * Treasury's back-to-back cover of a cross-currency payment of {@code amount} (ihb-fx D3, R5, R9): previewed on
         * T's payment account (T's deals in the pair first, then the live price), refused when the settle-currency
         * amount is over X's IHB headroom, then converted there -- on T's FX line, never X's. At market unless
         * {@code requested} names a price, quote or deal. Null for a same-currency route (nothing to convert).
         */
        public FxPaymentService.Conversion cover(FxPaymentService fx, BigDecimal amount, FxPaymentService.Instruction requested) {
            if (!crossCurrency()) return null;
            FxPaymentService.Instruction how = requested == null || requested.none() ? FxPaymentService.Instruction.AT_MARKET : requested;
            String ccy = enrolment.getCurrency();
            String refusal = refusal(fx.preview(paymentVa, ccy, amount, how).debitAmount());
            if (refusal != null) throw new BusinessException(refusal + " (" + ccy + " " + amount.toPlainString() + " at the indicative rate)");
            return fx.convert(paymentVa, ccy, amount, how);
        }
    }

    @Transactional(readOnly = true)
    public List<EnrolmentView> list(UUID corporateId) {
        return enrolments.findByCorporateIdOrderByCreatedAtDesc(corporateId).stream().map(this::view).toList();
    }

    /**
     * The route for a payment of {@code subsidiaryEntityId} in {@code currency} on {@code day}, when an enrolment is
     * active. Refused (not silently direct) when the enrolment's accounts no longer allow the posting.
     */
    @Transactional(readOnly = true)
    public Optional<Route> route(UUID subsidiaryEntityId, String currency, LocalDate day) {
        if (subsidiaryEntityId == null || currency == null) return Optional.empty();
        Optional<PoboEnrolment> active = enrolments.findBySubsidiaryEntityId(subsidiaryEntityId).stream()
            .filter(e -> e.getCurrency().equals(currency) && e.isActiveOn(day)).findFirst();
        if (active.isEmpty()) return Optional.empty();
        PoboEnrolment e = active.get();
        LegalEntity subsidiary = legalEntities.findById(e.getSubsidiaryEntityId())
            .orElseThrow(() -> new BusinessException("Enrolled subsidiary not found: " + e.getSubsidiaryEntityId()));
        LegalEntity payer = legalEntities.findById(e.getPayerEntityId())
            .orElseThrow(() -> new BusinessException("Enrolment's treasury centre not found: " + e.getPayerEntityId()));
        VirtualAccount paymentVa = accounts.findById(e.getPaymentVaId())
            .orElseThrow(() -> new BusinessException("Enrolment's payment account not found: " + e.getPaymentVaId()));
        VirtualAccount ihbCurrent = ihbCurrent(subsidiary, e.getIhbAccountId(), currency);
        String problem = paymentAccountProblem(paymentVa, payer.getId(), ihbCurrent.getCurrencyCode());
        if (problem != null) {
            throw new BusinessException("Cannot pay on behalf of " + subsidiary.getEntityCode() + ": payment account "
                + paymentVa.getVaNumber() + " " + problem + "; end the enrolment or fix the account");
        }
        return Optional.of(new Route(e, subsidiary, payer, paymentVa, ihbCurrent));
    }

    /**
     * Why a payable of {@code subsidiaryEntityId} in {@code currency} is not paid on behalf although the subsidiary is
     * enrolled in other currencies (D5), or null when it has no active enrolment at all.
     */
    @Transactional(readOnly = true)
    public String notEnrolledReason(UUID subsidiaryEntityId, String currency, LocalDate day) {
        if (subsidiaryEntityId == null) return null;
        List<PoboEnrolment> other = enrolments.findBySubsidiaryEntityId(subsidiaryEntityId).stream()
            .filter(e -> e.isActiveOn(day) && !e.getCurrency().equals(currency)).toList();
        if (other.isEmpty()) return null;
        String code = legalEntities.findById(subsidiaryEntityId).map(LegalEntity::getEntityCode).orElse("The subsidiary");
        return "Paid directly: " + code + " is not enrolled for " + currency + " (only "
            + String.join(", ", other.stream().map(PoboEnrolment::getCurrency).sorted().toList()) + ")";
    }

    @Transactional
    public EnrolmentView create(UUID corporateId, UUID subsidiaryEntityId, String currencyCode, UUID payerEntityId,
                                UUID paymentVaId, UUID ihbAccountId, LocalDate effectiveFrom) {
        if (corporateId == null || subsidiaryEntityId == null || payerEntityId == null || paymentVaId == null
            || currencyCode == null || currencyCode.isBlank()) {
            throw new BusinessException("Choose the corporate, subsidiary, currency, treasury centre and payment account");
        }
        String currency = currencyCode.trim().toUpperCase();
        LocalDate today = LocalDate.now();

        LegalEntity payer = entity(payerEntityId, corporateId);
        if (!Boolean.TRUE.equals(payer.getIsTreasuryCenter())) {
            throw new BusinessException(payer.getEntityCode() + " is not a treasury centre: only an entity flagged as "
                + "the treasury centre can pay on behalf of others");
        }
        LegalEntity subsidiary = entity(subsidiaryEntityId, corporateId);
        if (subsidiary.getId().equals(payer.getId())) {
            throw new BusinessException(subsidiary.getEntityCode() + " cannot pay on its own behalf; choose a subsidiary");
        }

        // R2: the accounts the in-house posting needs; the payment account is in its currency (ihb-fx R3)
        VirtualAccount ihbCurrent = ihbCurrent(subsidiary, ihbAccountId, currency);
        VirtualAccount paymentVa = accounts.findById(paymentVaId)
            .orElseThrow(() -> new BusinessException("Payment account not found: " + paymentVaId));
        String problem = paymentAccountProblem(paymentVa, payer.getId(), ihbCurrent.getCurrencyCode());
        if (problem != null) {
            throw new BusinessException("Payment account " + paymentVa.getVaNumber() + " " + problem);
        }

        // ponytail: checked here, not by a DB constraint (ddl-auto can't make a partial unique index), as COBO.
        enrolments.findBySubsidiaryEntityId(subsidiary.getId()).stream()
            .filter(e -> e.getCurrency().equals(currency) && e.isOpenOn(today)).findFirst().ifPresent(e -> {
                throw new BusinessException(subsidiary.getEntityCode() + " is already enrolled for " + currency
                    + "; end that enrolment first");
            });

        PoboEnrolment enrolment = PoboEnrolment.builder()
            .corporateId(corporateId)
            .subsidiaryEntityId(subsidiary.getId())
            .currency(currency)
            .payerEntityId(payer.getId())
            .paymentVaId(paymentVa.getId())
            .ihbAccountId(ihbAccountId)
            .effectiveFrom(effectiveFrom != null ? effectiveFrom : today)
            .build();
        enrolment.setCreatedBy(CurrentUser.name());
        PoboEnrolment saved = enrolments.save(enrolment);
        log.info("POBO enrolment: {} {} paid by {} from {}, settled on {}", subsidiary.getEntityCode(), currency,
            payer.getEntityCode(), paymentVa.getVaNumber(), ihbCurrent.getVaNumber());
        return view(saved);
    }

    /** Ends the enrolment today: payables paid from now on are paid directly; payments already made are unchanged. */
    @Transactional
    public EnrolmentView end(UUID id) {
        PoboEnrolment e = enrolments.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("POBO enrolment not found: " + id));
        LocalDate today = LocalDate.now();
        if (!e.isOpenOn(today)) {
            throw new BusinessException("This enrolment already ended on " + e.getEffectiveTo());
        }
        e.setEffectiveTo(today);
        e.setUpdatedBy(CurrentUser.name());
        // ihb-fx D7: its payables no longer wait for treasury's rate; they are paid directly from now on
        payables.findByCorporateIdAndFxStatus(e.getCorporateId(), Payable.AWAITING_FX).stream()
            .filter(p -> e.getSubsidiaryEntityId().equals(p.getOwningEntityId()) && e.getCurrency().equals(p.getCurrencyCode()))
            .forEach(p -> {
                p.setFxStatus(null);
                p.setFxStatusReason(null);
                payables.save(p);
            });
        return view(enrolments.save(e));
    }

    /** The treasury centre's accounts an enrolment settled in {@code currency} (its IHB account's) can pay from. */
    @Transactional(readOnly = true)
    public List<PaymentAccount> paymentAccounts(UUID payerEntityId, String currency) {
        return accounts.findByOwningEntityIdAndStatus(payerEntityId, VaStatus.ACTIVE).stream()
            .filter(va -> paymentAccountProblem(va, payerEntityId, currency) == null)
            .map(va -> new PaymentAccount(va.getId(), va.getVaNumber(), va.getVaName(), va.getCurrencyCode(), iban(va)))
            .toList();
    }

    /** Headroom on an IHB current account: its balance plus its IHB credit limit (D7). */
    public static BigDecimal headroom(VirtualAccount ihbCurrent) {
        return ihbCurrent.getIhbAvailableBalance();
    }

    /** Why paying {@code amount} from {@code ihbCurrent} is refused under D7, or null when it fits the headroom. */
    public static String headroomRefusal(VirtualAccount ihbCurrent, BigDecimal amount) {
        BigDecimal headroom = headroom(ihbCurrent);
        if (amount.compareTo(headroom) <= 0) return null;
        String owner = ihbCurrent.getOwningEntityCode() != null ? ihbCurrent.getOwningEntityCode() : ihbCurrent.getVaNumber();
        String ccy = ihbCurrent.getCurrencyCode();
        return "Refused: " + amount.toPlainString() + " " + ccy + " is over " + owner + "'s IHB credit headroom of "
            + headroom.toPlainString() + " " + ccy + " on " + ihbCurrent.getVaNumber() + " (balance "
            + ihbCurrent.getCurrentBalance() + ", credit limit "
            + (ihbCurrent.getEffectiveCreditLimit() != null ? ihbCurrent.getEffectiveCreditLimit() : BigDecimal.ZERO) + ")";
    }

    /** The IHB current account an enrolment settles on (ihb-fx D2): the subsidiary's active {@code ihbAccountId}, or when
     * null its active one in {@code currency}, as before D2. */
    private Optional<VirtualAccount> settleAccount(UUID subsidiaryId, UUID ihbAccountId, String currency) {
        return accounts.findByOwningEntityIdAndStatus(subsidiaryId, VaStatus.ACTIVE).stream()
            .filter(va -> va.getMirrorAccountType() == MirrorAccountType.IHB_CURRENT)
            .filter(va -> ihbAccountId != null ? ihbAccountId.equals(va.getId()) : currency.equals(va.getCurrencyCode()))
            .findFirst();
    }

    /** {@link #settleAccount} with the links the 6-leg posting needs (R2). */
    private VirtualAccount ihbCurrent(LegalEntity subsidiary, UUID ihbAccountId, String currency) {
        VirtualAccount current = settleAccount(subsidiary.getId(), ihbAccountId, currency)
            .orElseThrow(() -> new BusinessException(ihbAccountId != null
                ? "The account it settles on is not an active IHB current account of " + subsidiary.getEntityCode()
                : subsidiary.getEntityCode() + " has no active " + currency
                    + " IHB current account; open one on the In-House Bank page first"));
        if (!current.isIhbParticipant()) {
            throw new BusinessException("IHB current account " + current.getVaNumber() + " is not flagged as an IHB participant");
        }
        if (!activeAccount(current.getIhbSettlementVaId())) {
            throw new BusinessException(subsidiary.getEntityCode() + "'s IHB current account " + current.getVaNumber()
                + " has no active IHB settlement account");
        }
        if (!activeAccount(current.getIcReceivableVaId())) {
            throw new BusinessException(subsidiary.getEntityCode() + "'s IHB current account " + current.getVaNumber()
                + " has no active IC receivable at the treasury");
        }
        return current;
    }

    private boolean activeAccount(UUID id) {
        return id != null && accounts.findById(id).map(va -> va.getStatus() == VaStatus.ACTIVE).orElse(false);
    }

    /** Why {@code va} can't be the payment account, or null. Backed by a real bank account: its physical account has an
     * IBAN and that account's shadow (which the payout debits) is in the same program and currency. */
    private String paymentAccountProblem(VirtualAccount va, UUID payerEntityId, String currency) {
        if (!payerEntityId.equals(va.getOwningEntityId())) return "is not the treasury centre's";
        if (!currency.equals(va.getCurrencyCode())) return "is in " + va.getCurrencyCode() + ", not " + currency;
        if (va.getStatus() != VaStatus.ACTIVE) return "is not active";
        if (va.isSettlementVa()) return "is a settlement account";
        if (va.isIhbParticipant() || (va.getMirrorAccountType() != null && va.getMirrorAccountType() != MirrorAccountType.NONE)) {
            return "is an in-house account (" + (va.getMirrorAccountType() != null ? va.getMirrorAccountType() : "IHB") + ")";
        }
        if (va.getAccountCategory() != AccountCategory.TRANSACTION && va.getAccountCategory() != AccountCategory.DISBURSEMENT
            && va.getAccountCategory() != AccountCategory.COLLECTION) {
            return "is a " + va.getAccountCategory() + " account, not one that pays";
        }
        if (va.getProgramId() == null) return "is in no program, so no bank account backs it";
        if (iban(va) == null) return "has no bank account with an IBAN behind it";
        boolean shadow = accounts.findByLinkedPhysicalAccountIdAndAccountCategory(va.getPhysicalAccountId(), AccountCategory.PHYSICAL_MIRROR)
            .filter(s -> s.getStatus() == VaStatus.ACTIVE && Objects.equals(s.getProgramId(), va.getProgramId())
                && currency.equals(s.getCurrencyCode()))
            .isPresent();
        if (!shadow) return "has no active shadow of its bank account in its program to pay out through";
        return null;
    }

    /** The IBAN of the bank account behind {@code va}, or null: an enrolment's payment account and a pain.001 debtor account. */
    public String iban(VirtualAccount va) {
        return bankAccount(va).map(PhysicalAccount::getIban).filter(i -> !i.isBlank()).orElse(null);
    }

    /**
     * The debtor account of a pain.001 paying from {@code va}: its VIBAN, else its bank IBAN, else the IBAN of the bank
     * account behind it -- never a VA id. Refused when it has none.
     */
    public String debtorIban(VirtualAccount va) {
        return Optional.ofNullable(accountIban(va))
            .orElseThrow(() -> new BusinessException("Not available: no IBAN for the debtor account " + va.getVaNumber()));
    }

    /** The IBAN a message names for {@code va} (its VIBAN, else its bank IBAN, else its bank account's), or null. */
    public String accountIban(VirtualAccount va) {
        return Stream.of(va.getViban(), va.getBankIban(), iban(va)).filter(i -> i != null && !i.isBlank()).findFirst().orElse(null);
    }

    /**
     * The pain.001 debtor of a payment on behalf (D6): the treasury centre as debtor, its payment account's IBAN, that
     * bank account's BIC (null: the home bank's), and the subsidiary as ultimate debtor.
     */
    @Transactional(readOnly = true)
    public PainDebtor painDebtor(Route r) {
        String iban = iban(r.paymentVa());
        if (iban == null) {
            throw new BusinessException("Not available: no IBAN for the debtor account " + r.paymentVa().getVaNumber());
        }
        String bic = bankAccount(r.paymentVa()).map(PhysicalAccount::getBankCode).filter(b -> !b.isBlank()).orElse(null);
        return new PainDebtor(r.payer().getEntityName(), r.payer().getEntityCode(), iban, bic, r.subsidiary().getEntityName());
    }

    /**
     * The pain.001 debtor of any payment whose money leaves through the in-house posting, enrolled or not (D6): the
     * bank account the posting debits ({@code shadow}, the payout shadow of {@code payerVa}) with its IBAN and BIC (null:
     * the home bank's), the payer's owner (the treasury centre) as debtor, the owner of {@code ihbCurrent} as ultimate
     * debtor. Refused only when that bank account has no IBAN.
     */
    @Transactional(readOnly = true)
    public PainDebtor inHouseDebtor(VirtualAccount ihbCurrent, VirtualAccount payerVa, VirtualAccount shadow) {
        PhysicalAccount bank = Optional.ofNullable(shadow.getLinkedPhysicalAccountId()).flatMap(physicalAccounts::findById).orElse(null);
        if (bank == null || bank.getIban() == null || bank.getIban().isBlank()) {
            throw new BusinessException("Not available: no IBAN for the bank account "
                + (bank != null ? bank.getAccountNumber() : shadow.getVaNumber()) + " that " + payerVa.getVaNumber() + " pays out through");
        }
        LegalEntity payer = Optional.ofNullable(payerVa.getOwningEntityId()).flatMap(legalEntities::findById).orElse(null);
        String subsidiary = Optional.ofNullable(ihbCurrent.getOwningEntityId()).flatMap(legalEntities::findById)
            .map(LegalEntity::getEntityName).orElse(ihbCurrent.getOwningEntityCode() != null ? ihbCurrent.getOwningEntityCode() : ihbCurrent.getVaName());
        String bic = bank.getBankCode() != null && !bank.getBankCode().isBlank() ? bank.getBankCode() : null;
        return new PainDebtor(payer != null ? payer.getEntityName() : payerVa.getVaName(),
            payer != null ? payer.getEntityCode() : null, bank.getIban(), bic, subsidiary);
    }

    private Optional<PhysicalAccount> bankAccount(VirtualAccount va) {
        if (va.getPhysicalAccountId() == null) return Optional.empty();
        return physicalAccounts.findById(va.getPhysicalAccountId()).filter(PhysicalAccount::isOperational);
    }

    private LegalEntity entity(UUID id, UUID corporateId) {
        LegalEntity e = legalEntities.findById(id).orElseThrow(() -> new BusinessException("Legal entity not found: " + id));
        if (!corporateId.equals(e.getCorporateId())) {
            throw new BusinessException(e.getEntityCode() + " belongs to another corporate");
        }
        return e;
    }

    private EnrolmentView view(PoboEnrolment e) {
        Optional<LegalEntity> sub = legalEntities.findById(e.getSubsidiaryEntityId());
        Optional<LegalEntity> payer = legalEntities.findById(e.getPayerEntityId());
        Optional<VirtualAccount> va = accounts.findById(e.getPaymentVaId());
        Optional<VirtualAccount> current = settleAccount(e.getSubsidiaryEntityId(), e.getIhbAccountId(), e.getCurrency());
        return new EnrolmentView(e.getId(), e.getCorporateId(),
            e.getSubsidiaryEntityId(), sub.map(LegalEntity::getEntityCode).orElse(null), sub.map(LegalEntity::getEntityName).orElse(null),
            e.getCurrency(),
            e.getPayerEntityId(), payer.map(LegalEntity::getEntityCode).orElse(null), payer.map(LegalEntity::getEntityName).orElse(null),
            e.getPaymentVaId(), va.map(VirtualAccount::getVaNumber).orElse(null), va.map(VirtualAccount::getVaName).orElse(null),
            va.map(this::iban).orElse(null),
            current.map(VirtualAccount::getId).orElse(null), current.map(VirtualAccount::getVaNumber).orElse(null),
            current.map(VirtualAccount::getCurrencyCode).orElse(null),
            current.map(PoboEnrolmentService::headroom).orElse(null),
            e.getEffectiveFrom(), e.getEffectiveTo(), e.isActiveOn(LocalDate.now()), e.getCreatedBy(), e.getCreatedAt());
    }
}
