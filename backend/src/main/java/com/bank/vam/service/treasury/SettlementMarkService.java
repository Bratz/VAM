package com.bank.vam.service.treasury;

import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.VirtualAccount.AccountCategory;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.VirtualAccountRepository;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Sets and clears the settlement mark, with the hierarchy rules checked in both directions.
 *
 * <p>Two explicit transitions rather than a writable category. An account's category says what it is,
 * and letting that be written freely would make every pair of categories a legal move -- each with its
 * own structural invariant to re-check. The mark is one boolean with exactly two transitions, and both
 * have rules worth enforcing.
 *
 * <p><b>Setting</b> requires the category to permit the mark, and no marked sibling already serving the
 * same currency under the same parent. Two accounts both claiming to be *the* settlement account for a
 * currency under one aggregation is the ambiguity the reference model forbids, and whichever the
 * resolver happened to return first would decide where money went.
 *
 * <p><b>Clearing</b> is the harder half, because an account cannot be unmarked on its own evidence --
 * only on everyone else's. Two conditions apply and they are independent, mirroring the two searches:
 * a settlement account used for customer-internal prices may be released only if another in the same
 * currency exists at the same or a higher level, and one used for the results of non-transaction
 * accounts only if another exists at the same or a lower level. An account can be free for one purpose
 * and still required for the other, so both are evaluated and reported separately.
 *
 * <p>The decisive design point: this <b>runs the resolver</b> rather than restating its rules. It
 * clears the mark inside the transaction, flushes, asks the resolver where every account in the program
 * would settle now, and rolls back if any would be stranded. A re-implemented rules check is exactly
 * how a settlement account came to exist that satisfied every plausible rule while the resolver never
 * found it; the only check that cannot drift from the resolver is the resolver.
 *
 * <p>Blocking, not warning. An unresolved account parks its next payment to the exception account with
 * no signal, which is the failure this whole line of work started from.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementMarkService {

    private final VirtualAccountRepository vaRepository;
    private final SettlementVaResolverService resolver;

    /** One account that would stop resolving, and which search direction loses it. */
    @Getter
    @Builder
    public static class Stranded {
        private final UUID vaId;
        private final String vaNumber;
        private final String currencyCode;
        /** CONTRA for the upward search (customer-internal prices), RESULT for the downward one. */
        private final String direction;
    }

    @Getter
    @Builder
    public static class MarkVerdict {
        private final UUID vaId;
        private final String vaNumber;
        private final boolean currentlyMarked;
        private final boolean canSet;
        private final boolean canClear;
        /** Why setting is refused, empty when it is allowed. */
        private final List<String> setBlockers;
        /** Why clearing is refused, one entry per failing release condition. */
        private final List<String> clearBlockers;
        /** The accounts that would stop resolving if the mark were cleared. */
        private final List<Stranded> strandedIfCleared;
    }

    // ------------------------------------------------------------------------
    // Setting
    // ------------------------------------------------------------------------

    @Transactional
    public VirtualAccount set(UUID vaId) {
        VirtualAccount va = load(vaId);
        if (va.isSettlementVa()) {
            return va;   // already marked: nothing to do, and nothing to complain about
        }
        List<String> blockers = setBlockers(va);
        if (!blockers.isEmpty()) {
            throw new BusinessException("Cannot mark " + va.getVaNumber()
                + " as a settlement account: " + String.join("; ", blockers));
        }
        va.setSettlementMark(true);
        VirtualAccount saved = vaRepository.save(va);
        log.info("Settlement mark set on {} ({} under parent {})",
            saved.getVaNumber(), saved.getCurrencyCode(), saved.getParentAccountId());
        return saved;
    }

    private List<String> setBlockers(VirtualAccount va) {
        List<String> blockers = new ArrayList<>();

        // The reference model gates the mark on the account type category rather than on a list of
        // refusals, so this asks what the category permits. A container's balance is the sum of its
        // subordinates and it holds no transactions of its own, so it cannot be a settlement
        // destination however convenient its position looks.
        if (!va.isOperationalVa()) {
            blockers.add("its category is " + va.getAccountCategory()
                + ", and only an operational account (TRANSACTION, COLLECTION, DISBURSEMENT) can carry"
                + " the mark");
        }

        // Sibling uniqueness, per aggregation and currency.
        if (va.getParentAccountId() != null) {
            vaRepository.findByParentAccountIdAndSettlementMarkTrue(va.getParentAccountId()).stream()
                .filter(sibling -> !sibling.getId().equals(va.getId()))
                .filter(sibling -> va.getCurrencyCode() != null
                    && va.getCurrencyCode().equals(sibling.getCurrencyCode()))
                .findFirst()
                .ifPresent(clash -> blockers.add(clash.getVaNumber()
                    + " already carries the mark for " + clash.getCurrencyCode()
                    + " under the same parent"));
        }
        return blockers;
    }

    // ------------------------------------------------------------------------
    // Clearing
    // ------------------------------------------------------------------------

    @Transactional
    public VirtualAccount clear(UUID vaId) {
        VirtualAccount va = load(vaId);
        if (!va.isSettlementVa()) {
            return va;   // not marked: nothing to release
        }
        MarkVerdict verdict = evaluateClear(va);
        if (!verdict.isCanClear()) {
            // The mark was cleared and flushed to run the resolver against the hypothetical state, so
            // the transaction has to go back rather than be left half-applied.
            throw new BusinessException("Cannot clear the settlement mark on " + va.getVaNumber()
                + ": " + String.join("; ", verdict.getClearBlockers()));
        }
        log.info("Settlement mark cleared on {}", va.getVaNumber());
        return va;   // already cleared and flushed inside evaluateClear
    }

    /**
     * Asks the resolver what the world looks like without this mark.
     *
     * <p>Clears it, flushes so the resolver's own queries see the change, then walks the program:
     * every operational account must still find a contra settlement account, and every container must
     * still settle its results on one rather than falling through to the exception account. The caller
     * either commits this (clear succeeded) or throws (rolls it back).
     */
    private MarkVerdict evaluateClear(VirtualAccount va) {
        va.setSettlementMark(false);
        vaRepository.saveAndFlush(va);

        List<Stranded> stranded = new ArrayList<>();
        for (VirtualAccount other : vaRepository.findByProgramId(va.getProgramId())) {
            if (other.getId().equals(va.getId())) {
                continue;
            }
            AccountCategory category = other.getAccountCategory();
            if (category != null && SettlementVaResolverService.CONTAINER_CATEGORIES.contains(category)) {
                // Downward: a container's result must land on a settlement account, not the exception
                // account. settlementAccountForResultOn falls back rather than failing, so the test is
                // what it returned, not whether it threw.
                VirtualAccount target = resolver.settlementAccountForResultOn(other);
                if (target == null || !target.isSettlementVa()) {
                    stranded.add(strandedOf(other, "RESULT"));
                }
            } else if (other.isOperationalVa()) {
                // Upward: the contra leg of a price charged to this account.
                if (resolver.resolveSettlementVaWithResult(other).requiresExceptionHandling()) {
                    stranded.add(strandedOf(other, "CONTRA"));
                }
            }
        }

        List<String> blockers = new ArrayList<>();
        long contra = stranded.stream().filter(s -> "CONTRA".equals(s.getDirection())).count();
        long result = stranded.stream().filter(s -> "RESULT".equals(s.getDirection())).count();
        // Reported separately because the two conditions are independent: an account can be free for
        // one purpose while still required for the other.
        if (contra > 0) {
            blockers.add(contra + " account(s) would have no settlement account for customer-internal"
                + " prices, and no other marked account in the currency sits at the same or a higher"
                + " level: " + names(stranded, "CONTRA"));
        }
        if (result > 0) {
            blockers.add(result + " non-transaction account(s) would have nowhere to settle their"
                + " results, and no other marked account in the currency sits at the same or a lower"
                + " level: " + names(stranded, "RESULT"));
        }
        return MarkVerdict.builder()
            .vaId(va.getId())
            .vaNumber(va.getVaNumber())
            .currentlyMarked(true)
            .canSet(false)
            .canClear(blockers.isEmpty())
            .setBlockers(List.of())
            .clearBlockers(blockers)
            .strandedIfCleared(stranded)
            .build();
    }

    // ------------------------------------------------------------------------
    // Preview
    // ------------------------------------------------------------------------

    /**
     * The verdict without applying it, so a screen can state the outcome before submit.
     *
     * <p>A new transaction that is always rolled back: evaluating a clear means actually clearing the
     * mark so the resolver can be run against the hypothetical state, and that must never escape. It is
     * REQUIRES_NEW so marking it rollback-only cannot poison a caller's transaction -- the trap where
     * an inner rollback-only makes an outer commit fail with UnexpectedRollbackException and destroys
     * the real message.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public MarkVerdict preview(UUID vaId) {
        try {
            VirtualAccount va = load(vaId);
            if (va.isSettlementVa()) {
                return evaluateClear(va);
            }
            List<String> blockers = setBlockers(va);
            return MarkVerdict.builder()
                .vaId(va.getId())
                .vaNumber(va.getVaNumber())
                .currentlyMarked(false)
                .canSet(blockers.isEmpty())
                .canClear(false)
                .setBlockers(blockers)
                .clearBlockers(List.of())
                .strandedIfCleared(List.of())
                .build();
        } finally {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        }
    }

    /**
     * Whether an account that does not exist yet could carry the mark at a chosen placement.
     *
     * <p>A create screen needs the verdict before submit, but {@link #preview(UUID)} needs an id. The
     * only rule that can refuse a brand-new operational account is sibling uniqueness, because the
     * category is known to be permitted and nothing depends on it yet -- so this answers exactly that,
     * for a parent and a currency, and reads nothing else.
     */
    @Transactional(readOnly = true)
    public MarkVerdict previewPlacement(UUID parentVaId, String currencyCode) {
        List<String> blockers = new ArrayList<>();
        if (parentVaId != null && currencyCode != null) {
            vaRepository.findByParentAccountIdAndSettlementMarkTrue(parentVaId).stream()
                .filter(sibling -> currencyCode.equals(sibling.getCurrencyCode()))
                .findFirst()
                .ifPresent(clash -> blockers.add(clash.getVaNumber()
                    + " already carries the mark for " + clash.getCurrencyCode()
                    + " under the same parent"));
        }
        return MarkVerdict.builder()
            .vaId(null)
            .vaNumber(null)
            .currentlyMarked(false)
            .canSet(blockers.isEmpty())
            .canClear(false)
            .setBlockers(blockers)
            .clearBlockers(List.of())
            .strandedIfCleared(List.of())
            .build();
    }

    // ------------------------------------------------------------------------

    private VirtualAccount load(UUID vaId) {
        return vaRepository.findById(vaId)
            .orElseThrow(() -> new ResourceNotFoundException("Virtual account not found: " + vaId));
    }

    private Stranded strandedOf(VirtualAccount va, String direction) {
        return Stranded.builder()
            .vaId(va.getId())
            .vaNumber(va.getVaNumber())
            .currencyCode(va.getCurrencyCode())
            .direction(direction)
            .build();
    }

    private String names(List<Stranded> stranded, String direction) {
        return stranded.stream()
            .filter(s -> direction.equals(s.getDirection()))
            .limit(10)
            .map(Stranded::getVaNumber)
            .reduce((a, b) -> a + ", " + b)
            .orElse("");
    }
}
