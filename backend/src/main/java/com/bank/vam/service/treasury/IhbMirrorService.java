package com.bank.vam.service.treasury;

import com.bank.vam.entity.Transaction;
import com.bank.vam.entity.Transaction.MovementType;
import com.bank.vam.entity.Transaction.TransactionCategory;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.repository.TransactionRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Treasury's mirrors of a subsidiary's IHB current account follow its position P (POBO spec R10):
 * P &lt; 0 -> IC receivable = -P, IC payable = 0 (the subsidiary owes treasury);
 * P &ge; 0 -> IC payable = P, IC receivable = 0 (treasury owes the subsidiary).
 * Called after every movement of an IHB current account; postings never move the mirrors themselves.
 * Repositories only, so any posting service can depend on it without a cycle.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IhbMirrorService {

    private final VirtualAccountRepository virtualAccountRepository;
    private final TransactionRepository transactionRepository;

    /**
     * Sets the IC receivable / IC payable mirrors of {@code ihbCurrentVa} (found through its link fields; a missing
     * mirror is skipped) from its current balance, with one ledger row per mirror whose balance changes. Idempotent:
     * mirrors already at the rule post nothing. Any other account (no mirror links) is ignored.
     *
     * @param reason what moved the IHB current account, quoted on the mirror's ledger row
     */
    @Transactional
    public void syncIhbMirrors(VirtualAccount ihbCurrentVa, String reason, String correlationId) {
        // Its credit use follows the same position (D7): down on a credit too, not only up on a debit.
        if (ihbCurrentVa != null && ihbCurrentVa.isIhbParticipant() && ihbCurrentVa.followIhbCreditUse()) {
            virtualAccountRepository.save(ihbCurrentVa);
        }
        if (ihbCurrentVa == null ||(ihbCurrentVa.getIcReceivableVaId() == null && ihbCurrentVa.getIcPayableVaId() == null)) {
            return;
        }
        BigDecimal position = ihbCurrentVa.getCurrentBalance() != null ? ihbCurrentVa.getCurrentBalance() : BigDecimal.ZERO;
        follow(ihbCurrentVa, ihbCurrentVa.getIcReceivableVaId(), position.negate().max(BigDecimal.ZERO),
            MovementType.IC_RECEIVABLE, "IC Receivable", position, reason, correlationId);
        follow(ihbCurrentVa, ihbCurrentVa.getIcPayableVaId(), position.max(BigDecimal.ZERO),
            MovementType.IC_PAYABLE, "IC Payable", position, reason, correlationId);
    }

    private void follow(VirtualAccount ihbCurrentVa, UUID mirrorId, BigDecimal target, MovementType increaseType,
                        String label, BigDecimal position, String reason, String correlationId) {
        if (mirrorId == null) return;
        VirtualAccount mirror = virtualAccountRepository.findById(mirrorId).orElse(null);
        if (mirror == null) return;
        BigDecimal before = mirror.getCurrentBalance() != null ? mirror.getCurrentBalance() : BigDecimal.ZERO;
        BigDecimal change = target.subtract(before);
        if (change.signum() == 0) return;

        mirror.setCurrentBalance(target);
        virtualAccountRepository.save(mirror);

        String owner = ihbCurrentVa.getOwningEntityCode() != null ? ihbCurrentVa.getOwningEntityCode() : ihbCurrentVa.getVaNumber();
        // Increases keep the mirror's own type; decreases use IC_SETTLEMENT like every other IC mirror decrease
        // (IC_PAYABLE reads as a credit on a statement, which a decrease is not).
        MovementType type = change.signum() > 0 ? increaseType : MovementType.IC_SETTLEMENT;
        transactionRepository.save(Transaction.builder()
            .movementType(type)
            .transactionCategory(TransactionCategory.INTERNAL)
            .corporateId(mirror.getCorporateId())
            .legalEntityId(mirror.getOwningEntityId())
            .vaId(mirror.getId())
            .programId(mirror.getProgramId())
            .amount(change.abs())
            .currencyCode(mirror.getCurrencyCode())
            .balanceBefore(before)
            .balanceAfter(target)
            .transactionDate(LocalDateTime.now())
            .valueDate(LocalDate.now())
            .referenceNumber(Transaction.generateReference(type))
            .description(label + " follows " + owner + "'s IHB position " + position.toPlainString() + " "
                + ihbCurrentVa.getCurrencyCode() + ": " + reason)
            .channel("IHB")
            .counterpartyVaId(ihbCurrentVa.getId())
            .correlationId(correlationId)
            .behalfOfEntity(owner)
            .behalfOfVaId(ihbCurrentVa.getId())
            .status(Transaction.TransactionStatus.COMPLETED)
            .processingNotes(label + (change.signum() > 0 ? " increase" : " decrease")
                + " following the IHB current account " + ihbCurrentVa.getVaNumber())
            .build());
        log.info("{} {} {} -> {} following {} position {}", label, mirror.getVaNumber(), before, target, owner, position);
    }
}
