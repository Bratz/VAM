package com.bank.vam.service.intercompany;

import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.hierarchy.LegalEntity;
import com.bank.vam.entity.payables.Payable;
import com.bank.vam.entity.pobo.IntercompanyRecharge;
import com.bank.vam.entity.receivables.Receivable;
import com.bank.vam.entity.treasury.NettingCycle;
import com.bank.vam.entity.treasury.NettingEntry;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.hierarchy.LegalEntityRepository;
import com.bank.vam.repository.payables.PayableRepository;
import com.bank.vam.repository.pobo.IntercompanyRechargeRepository;
import com.bank.vam.repository.receivables.ReceivableRepository;
import com.bank.vam.repository.treasury.NettingCycleRepository;
import com.bank.vam.repository.treasury.NettingEntryRepository;
import com.bank.vam.service.treasury.NettingService;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * The Intercompany page's reads beside the group position: per-subsidiary IHB positions from the IC mirrors, and the
 * netting hand-off (what a pair could net now, and adding it to one of the corporate's cycles). The legacy
 * intercompany_transactions table and everything built on it are gone (spec D3, R12).
 */
// ponytail: keeps its old name (IntercompanyGroupPositionService and the tests inject it); rename if it grows.
@Slf4j
@Service
@RequiredArgsConstructor
public class IntercompanyTransactionService {

    private final IntercompanyRechargeRepository rechargeRepository;
    private final LegalEntityRepository legalEntityRepository;
    private final PayableRepository payableRepository;
    private final ReceivableRepository receivableRepository;
    private final NettingCycleRepository nettingCycleRepository;
    private final NettingEntryRepository nettingEntryRepository;
    private final VirtualAccountRepository virtualAccountRepository;
    private final NettingService nettingService;

    /**
     * Check if entity pair is eligible for netting.
     */
    @Transactional(readOnly = true)
    public boolean checkNettingEligibility(LegalEntity entity1, LegalEntity entity2) {
        // Both must be netting enabled
        if (!Boolean.TRUE.equals(entity1.getCanParticipateNetting()) || 
            !Boolean.TRUE.equals(entity2.getCanParticipateNetting())) {
            return false;
        }

        // Must be same corporate
        if (!entity1.getCorporateId().equals(entity2.getCorporateId())) {
            return false;
        }

        // Both must be active
        if (entity1.getStatus() != LegalEntity.EntityStatus.ACTIVE ||
            entity2.getStatus() != LegalEntity.EntityStatus.ACTIVE) {
            return false;
        }

        return true;
    }

    /**
     * What a netting cycle would take now for one pair of the corporate's entities (spec R5): the pair's open
     * intercompany invoices that pass netting's own checks, each invoice once (a payable linked to one of the group's
     * receivables is netted as that receivable, {@link IntercompanyGroupPositionService#countedAsReceivable}), and the
     * recharges awaiting netting that are in no cycle yet; plus the corporate's cycles still taking entries. Amounts
     * stay per item and currency (this used to sum currencies and stamp AED).
     */
    @Transactional(readOnly = true)
    public NettingEligibilityResult getNettingEligibleTransactions(UUID corporateId, UUID entity1Id, UUID entity2Id) {
        Map<UUID, LegalEntity> group = groupOf(corporateId);
        LegalEntity e1 = group.get(entity1Id);
        LegalEntity e2 = group.get(entity2Id);
        if (e1 == null || e2 == null || e1 == e2) {
            throw new BusinessException("Both entities must be two different entities of corporate " + corporateId);
        }
        Set<UUID> pair = Set.of(entity1Id, entity2Id);
        List<NettingItem> items = new ArrayList<>();
        String reason = checkNettingEligibility(e1, e2) ? null
            : e1.getEntityCode() + " and " + e2.getEntityCode() + " must both be active and take part in netting";
        if (reason == null) {
            List<Receivable> icReceivables = receivableRepository.findIntercompanyReceivablesByCorporate(corporateId);
            for (Receivable r : receivableRepository.findNettingEligibleReceivables(corporateId)) {
                if (between(pair, r.getOwningEntityId(), r.getIntercompanyEntityId())) {
                    items.add(new NettingItem("RECEIVABLE", r.getId(), r.getReceivableNumber(),
                        group.get(r.getIntercompanyEntityId()).getEntityCode(), group.get(r.getOwningEntityId()).getEntityCode(),
                        r.getNetAmount(), r.getCurrencyCode()));
                }
            }
            for (Payable p : payableRepository.findNettingEligiblePayables(corporateId)) {
                if (between(pair, p.getOwningEntityId(), p.getCounterpartyEntityId())
                        && !IntercompanyGroupPositionService.countedAsReceivable(p, icReceivables)) {
                    items.add(new NettingItem("PAYABLE", p.getId(), p.getPayableNumber(),
                        group.get(p.getOwningEntityId()).getEntityCode(), group.get(p.getCounterpartyEntityId()).getEntityCode(),
                        p.getNetAmount(), p.getCurrencyCode()));
                }
            }
            for (IntercompanyRecharge r : rechargeRepository.findAll()) {
                if (NettingService.awaitingNetting(r) && between(pair, r.getPayerEntityId(), r.getBehalfEntityId())
                        && !nettingEntryRepository.existsByIntercompanyRechargeId(r.getId())) {
                    boolean cobo = r.getRechargeType() == IntercompanyRecharge.RechargeType.COBO_COLLECTION;
                    UUID debtor = cobo ? r.getPayerEntityId() : r.getBehalfEntityId();
                    UUID creditor = cobo ? r.getBehalfEntityId() : r.getPayerEntityId();
                    items.add(new NettingItem("RECHARGE", r.getId(), r.getRechargeReference(),
                        group.get(debtor).getEntityCode(), group.get(creditor).getEntityCode(),
                        r.getTotalRecharge(), r.getCurrencyCode()));
                }
            }
        }

        return NettingEligibilityResult.builder()
            .entity1Id(entity1Id)
            .entity2Id(entity2Id)
            .reason(reason)
            .items(items)
            .openCycles(openCyclesOf(corporateId))
            .build();
    }

    private Map<UUID, LegalEntity> groupOf(UUID corporateId) {
        Map<UUID, LegalEntity> group = new LinkedHashMap<>();
        legalEntityRepository.findByCorporateIdOrderByHierarchyPath(corporateId).forEach(e -> group.put(e.getId(), e));
        return group;
    }

    /** Both sides are in the set (the group's or the pair's entities) and they are two different entities. */
    private static boolean between(Set<UUID> entityIds, UUID a, UUID b) {
        return a != null && b != null && !a.equals(b) && entityIds.contains(a) && entityIds.contains(b);
    }

    /**
     * The corporate's cycles still taking entries, leaving out any holding an entry whose source was already settled
     * outside netting (NettingService.entriesSettledAtSource): adding to it would hand the treasurer a cycle that pays
     * twice.
     */
    private List<OpenCycle> openCyclesOf(UUID corporateId) {
        return nettingCycleRepository.findByCorporateIdOrderByCreatedAtDesc(corporateId).stream()
            .filter(NettingCycle::acceptsEntries)
            .filter(c -> nettingService.entriesSettledAtSource(c.getId()).isEmpty())
            .sorted(Comparator.comparing(NettingCycle::getCycleReference))
            .map(c -> new OpenCycle(c.getId(), c.getCycleReference(), c.getCycleName(), c.getStatus().name(),
                c.getBaseCurrency()))
            .collect(Collectors.toList());
    }

    /**
     * Add the corporate's IC items to one of its netting cycles. Entry creation stays with {@link NettingService}, the
     * canonical owner of {@link NettingEntry} construction. Everything is checked before anything is added: the cycle
     * takes entries and is the corporate's, and each item is between two of the corporate's entities and still
     * nettable (a payable linked to a receivable is netted as the receivable).
     */
    @Transactional
    public AddToNettingResult addToNettingCycle(UUID corporateId, UUID cycleId,
                                                List<UUID> rechargeIds, List<UUID> payableIds, List<UUID> receivableIds) {
        Set<UUID> group = groupOf(corporateId).keySet();
        NettingCycle cycle = nettingCycleRepository.findById(cycleId)
            .orElseThrow(() -> new ResourceNotFoundException("Netting cycle not found: " + cycleId));
        if (!cycle.acceptsEntries()) {
            throw new BusinessException("Netting cycle is not open for additions");
        }
        if (!corporateId.equals(cycle.getCorporateId())) {
            throw new BusinessException("Netting cycle " + cycle.getCycleReference() + " is not this corporate's");
        }
        List<String> stale = nettingService.entriesSettledAtSource(cycleId);
        if (!stale.isEmpty()) {
            throw new BusinessException("Netting cycle " + cycle.getCycleReference() + " holds entries already settled "
                + "outside netting: " + String.join(", ", stale));
        }

        List<IntercompanyRecharge> recharges = new ArrayList<>();
        for (UUID id : nz(rechargeIds)) {
            IntercompanyRecharge r = rechargeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Recharge not found: " + id));
            if (!between(group, r.getPayerEntityId(), r.getBehalfEntityId())) throw notTheCorporates(r.getRechargeReference());
            if (!NettingService.awaitingNetting(r) || nettingEntryRepository.existsByIntercompanyRechargeId(r.getId())) {
                throw new BusinessException("Recharge " + r.getRechargeReference() + " is not awaiting netting");
            }
            recharges.add(r);
        }
        List<Receivable> icReceivables = receivableRepository.findIntercompanyReceivablesByCorporate(corporateId);
        List<Payable> payables = new ArrayList<>();
        for (UUID id : nz(payableIds)) {
            Payable p = payableRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Payable not found: " + id));
            if (!corporateId.equals(p.getCorporateId()) || !between(group, p.getOwningEntityId(), p.getCounterpartyEntityId())) {
                throw notTheCorporates(p.getPayableNumber());
            }
            if (IntercompanyGroupPositionService.countedAsReceivable(p, icReceivables)) {
                throw new BusinessException("Payable " + p.getPayableNumber()
                    + " is the other side of an intercompany receivable; add the receivable");
            }
            if (!p.canAddToNetting()) {
                throw new BusinessException("Payable " + p.getPayableNumber() + " is not eligible for netting");
            }
            payables.add(p);
        }
        List<Receivable> receivables = new ArrayList<>();
        for (UUID id : nz(receivableIds)) {
            Receivable r = receivableRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Receivable not found: " + id));
            if (!corporateId.equals(r.getCorporateId()) || !between(group, r.getOwningEntityId(), r.getIntercompanyEntityId())) {
                throw notTheCorporates(r.getReceivableNumber());
            }
            receivables.add(r);
        }

        recharges.forEach(r -> nettingService.addRechargeToCycle(cycleId, r));
        payables.forEach(p -> nettingService.addPayableToCycle(cycleId, p));
        receivables.forEach(r -> nettingService.addReceivableToCycle(cycleId, r));

        return AddToNettingResult.builder()
            .cycleId(cycleId)
            .cycleReference(cycle.getCycleReference())
            .rechargesAdded(recharges.size())
            .payablesAdded(payables.size())
            .receivablesAdded(receivables.size())
            .totalAdded(recharges.size() + payables.size() + receivables.size())
            .build();
    }

    private static List<UUID> nz(List<UUID> ids) {
        return ids != null ? ids : List.of();
    }

    private static BusinessException notTheCorporates(String reference) {
        return new BusinessException(reference + " is not between two entities of this corporate");
    }

    @Data
    @Builder
    public static class SubsidiaryIntercompanyPosition {
        private UUID subsidiaryEntityId;
        private String subsidiaryEntityCode;
        private String subsidiaryEntityName;
        private UUID treasuryEntityId;        // the treasury centre on the other side (owns the IC mirrors)
        private String treasuryEntityCode;
        private String currencyCode;
        private BigDecimal receivableBalance; // Treasury's claim on subsidiary (POBO)
        private BigDecimal payableBalance;    // Treasury's obligation to subsidiary (COBO)
        private BigDecimal netPosition;       // receivable - payable; positive = subsidiary owes Treasury
    }

    /**
     * Per-subsidiary intercompany position, sourced directly from the real IC
     * Receivable / IC Payable VA balances - not from summing IntercompanyRecharge rows,
     * which are not the source of truth for it. One row per
     * (subsidiary, currency): a subsidiary with multiple IHB Current Accounts in
     * different currencies gets one row per currency, matching how every other
     * FX-honest breakdown on this dashboard refuses to fabricate a cross-currency sum.
     */
    @Transactional(readOnly = true)
    public List<SubsidiaryIntercompanyPosition> getIntercompanyPositions(UUID corporateId) {
        List<VirtualAccount> icVas = virtualAccountRepository.findByCorporateId(corporateId).stream()
            .filter(va -> va.getMirrorAccountType() == VirtualAccount.MirrorAccountType.IC_RECEIVABLE
                       || va.getMirrorAccountType() == VirtualAccount.MirrorAccountType.IC_PAYABLE)
            .filter(va -> va.getMirrorsVaId() != null)
            .collect(Collectors.toList());

        record Key(UUID subsidiaryId, String currency) {}
        Map<Key, BigDecimal[]> totals = new LinkedHashMap<>(); // [receivable, payable]
        Map<Key, UUID> treasuryIdByKey = new LinkedHashMap<>();

        for (VirtualAccount va : icVas) {
            VirtualAccount ihbCurrentAccount = virtualAccountRepository.findById(va.getMirrorsVaId()).orElse(null);
            if (ihbCurrentAccount == null || ihbCurrentAccount.getOwningEntityId() == null) {
                continue;
            }
            Key key = new Key(ihbCurrentAccount.getOwningEntityId(), va.getCurrencyCode());
            BigDecimal[] slot = totals.computeIfAbsent(key, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            BigDecimal balance = va.getCurrentBalance() != null ? va.getCurrentBalance() : BigDecimal.ZERO;
            if (va.getMirrorAccountType() == VirtualAccount.MirrorAccountType.IC_RECEIVABLE) {
                slot[0] = slot[0].add(balance);
            } else {
                slot[1] = slot[1].add(balance);
            }
            treasuryIdByKey.putIfAbsent(key, va.getOwningEntityId()); // treasury owns the IC mirrors
        }

        List<SubsidiaryIntercompanyPosition> result = new ArrayList<>();
        for (Map.Entry<Key, BigDecimal[]> entry : totals.entrySet()) {
            BigDecimal receivable = entry.getValue()[0];
            BigDecimal payable = entry.getValue()[1];
            if (receivable.signum() == 0 && payable.signum() == 0) {
                continue;
            }
            LegalEntity subsidiary = legalEntityRepository.findById(entry.getKey().subsidiaryId()).orElse(null);
            UUID treasuryId = treasuryIdByKey.get(entry.getKey());
            LegalEntity treasury = treasuryId != null ? legalEntityRepository.findById(treasuryId).orElse(null) : null;
            result.add(SubsidiaryIntercompanyPosition.builder()
                .subsidiaryEntityId(entry.getKey().subsidiaryId())
                .subsidiaryEntityCode(subsidiary != null ? subsidiary.getEntityCode() : null)
                .subsidiaryEntityName(subsidiary != null ? subsidiary.getEntityName() : null)
                .treasuryEntityId(treasuryId)
                .treasuryEntityCode(treasury != null ? treasury.getEntityCode() : null)
                .currencyCode(entry.getKey().currency())
                .receivableBalance(receivable)
                .payableBalance(payable)
                .netPosition(receivable.subtract(payable))
                .build());
        }
        result.sort(Comparator.comparing((SubsidiaryIntercompanyPosition s) -> s.getNetPosition().abs()).reversed());
        return result;
    }

    @Data
    @Builder
    public static class NettingEligibilityResult {
        private UUID entity1Id;
        private UUID entity2Id;
        private String reason;               // why the pair can't be netted at all; null when it can
        private List<NettingItem> items;     // what a cycle would take now, one per invoice or recharge
        private List<OpenCycle> openCycles;  // the corporate's cycles still taking entries
    }

    /** One nettable item: kind RECEIVABLE, PAYABLE or RECHARGE; the debtor owes the creditor the amount. */
    public record NettingItem(String kind, UUID id, String reference, String debtorCode, String creditorCode,
                              BigDecimal amount, String currency) {}

    public record OpenCycle(UUID id, String cycleReference, String cycleName, String status, String baseCurrency) {}

    @Data
    @Builder
    public static class AddToNettingResult {
        private UUID cycleId;
        private String cycleReference;
        private int rechargesAdded;
        private int payablesAdded;
        private int receivablesAdded;
        private int totalAdded;
    }

}
