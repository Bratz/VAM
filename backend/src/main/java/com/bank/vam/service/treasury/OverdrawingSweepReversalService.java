package com.bank.vam.service.treasury;

import com.bank.vam.entity.Transaction;
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
import java.util.Optional;

/**
 * Reverses sweeps that moved more money than the source account held.
 *
 * <p>Live case: on 2026-09-15 a ZERO_BALANCE rule moved 1,000,000.96 out of
 * IHB-MNC-UAE-DUBAI-AED-0001, which held 0.96, and credited FINALI-L6-AGG-U-8117. The cause is
 * fixed — sweeps size on cash now, and a container can no longer be a target — but the posting
 * stands, and it cannot be corrected by recomputing a column: real balances moved on both sides, so
 * it needs a compensating pair with its own ledger rows.
 *
 * <p>Found by shape rather than by id (a SWEEP_OUT whose amount exceeded the balance it was drawn
 * from, leaving the source negative), so any sibling occurrence is covered too.
 *
 * <p>Every precondition is re-checked against current balances before anything moves, and a
 * mismatch skips rather than guesses: if either side has been touched since, the original is no
 * longer the whole story and a blind reversal would invent a new error. Idempotent — a correlation
 * that already carries its reversal is left alone.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OverdrawingSweepReversalService {

    static final String REVERSAL_PREFIX = "REV-";

    private final TransactionRepository transactionRepository;
    private final VirtualAccountRepository virtualAccountRepository;

    @Transactional
    public int reverseOverdrawingSweeps() {
        int reversed = 0;
        int skipped = 0;

        for (Transaction sweep : transactionRepository.findOverdrawingSweeps()) {
            String reversalCorrelation = REVERSAL_PREFIX + sweep.getCorrelationId();
            // existsBy..., not findByCorrelationId: that one returns Optional and throws on more
            // than one row, and a reversal is a pair. Caught by running the endpoint twice -- the
            // first call succeeded and the second failed with NonUniqueResultException.
            if (sweep.getCorrelationId() == null
                    || transactionRepository.existsByCorrelationId(reversalCorrelation)) {
                continue; // already reversed, or nothing to key the reversal to
            }

            Optional<VirtualAccount> sourceOpt = virtualAccountRepository.findById(sweep.getVaId());
            Optional<VirtualAccount> targetOpt = sweep.getCounterpartyVaId() == null
                    ? Optional.empty() : virtualAccountRepository.findById(sweep.getCounterpartyVaId());
            if (sourceOpt.isEmpty() || targetOpt.isEmpty()) {
                log.warn("Overdrawing-sweep reversal: skipping {} — source or counterparty account is gone",
                        sweep.getReferenceNumber());
                skipped++;
                continue;
            }

            VirtualAccount source = sourceOpt.get();
            VirtualAccount target = targetOpt.get();
            BigDecimal amount = sweep.getAmount();

            // Only reverse an untouched posting. If the source has moved off the balance the sweep
            // left it on, or the target no longer holds at least what it was credited, something
            // else has happened since and putting this amount back would be guesswork.
            if (source.getCurrentBalance().compareTo(sweep.getBalanceAfter()) != 0
                    || target.getCurrentBalance().compareTo(amount) < 0) {
                log.warn("Overdrawing-sweep reversal: skipping {} — balances moved since "
                                + "(source {} expected {}, target {} holds {} of {})",
                        sweep.getReferenceNumber(), source.getVaNumber(), sweep.getBalanceAfter(),
                        target.getVaNumber(), target.getCurrentBalance(), amount);
                skipped++;
                continue;
            }

            BigDecimal targetBefore = target.getCurrentBalance();
            target.debit(amount);
            BigDecimal sourceBefore = source.getCurrentBalance();
            source.credit(amount);
            virtualAccountRepository.save(target);
            virtualAccountRepository.save(source);

            String note = "Reversal of overdrawing sweep " + sweep.getReferenceNumber()
                    + " — swept " + amount + " from a balance of " + sweep.getBalanceBefore();

            transactionRepository.save(movement(target, Transaction.MovementType.REVERSAL, amount,
                    targetBefore, target.getCurrentBalance(), source.getId(), reversalCorrelation, note));
            transactionRepository.save(movement(source, Transaction.MovementType.REVERSAL, amount,
                    sourceBefore, source.getCurrentBalance(), target.getId(), reversalCorrelation, note));

            log.info("Reversed overdrawing sweep {}: {} back to {}, {} back to {}",
                    sweep.getReferenceNumber(), target.getVaNumber(), target.getCurrentBalance(),
                    source.getVaNumber(), source.getCurrentBalance());
            reversed++;
        }

        if (reversed > 0 || skipped > 0) {
            log.info("Overdrawing-sweep reversal: reversed={}, skipped={}", reversed, skipped);
        }
        return reversed;
    }

    private Transaction movement(VirtualAccount va, Transaction.MovementType type, BigDecimal amount,
                                 BigDecimal before, BigDecimal after, java.util.UUID counterparty,
                                 String correlationId, String description) {
        return Transaction.builder()
                .referenceNumber(Transaction.generateReference(type))
                .movementType(type)
                .corporateId(va.getCorporateId())
                .vaId(va.getId())
                .physicalAccountId(va.getPhysicalAccountId())
                .programId(va.getProgramId())
                .amount(amount)
                .currencyCode(va.getCurrencyCode())
                .balanceBefore(before)
                .balanceAfter(after)
                .counterpartyVaId(counterparty)
                .correlationId(correlationId)
                .description(description)
                .status(Transaction.TransactionStatus.COMPLETED)
                .transactionDate(LocalDateTime.now())
                .valueDate(LocalDate.now())
                .channel("TREASURY")
                .build();
    }
}
