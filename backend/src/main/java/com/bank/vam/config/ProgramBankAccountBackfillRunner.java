package com.bank.vam.config;

import com.bank.vam.entity.PhysicalAccount;
import com.bank.vam.entity.Program;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.VirtualAccount.AccountCategory;
import com.bank.vam.entity.hierarchy.LegalEntity;
import com.bank.vam.repository.PhysicalAccountRepository;
import com.bank.vam.repository.ProgramRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.hierarchy.LegalEntityRepository;
import com.bank.vam.service.treasury.ShadowAccountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Data repair: every program holding active transaction accounts in a currency gets a bank account of its own in
 * that currency, at the home bank, where it has none.
 *
 * <p>Since payments settle only through the paying program's own bank account (commit a64b8c7; before, they fell
 * back to any of the corporate's), programs set up without one could no longer pay or collect: in the demo data FINAL
 * IHB, Main Coll VAs and Test Multi-Currency Program, whose corporate's real accounts all belong to a fourth program.
 * Each new account is a current account at the home bank for the program's main entity, opened with a balance equal
 * to what the program's accounts hold in that currency, so the bank and the books agree; it is attached through
 * {@link ShadowAccountService#createShadowAccountForProgram}, as program setup does, under the program's aggregation
 * in that currency. Idempotent: a program and currency with a bank account are left alone. Closed programs are skipped.
 *
 * <p>A runner rather than a Flyway migration because the mirror's place in the hierarchy (parent, path, owner) is
 * worked out by the service; SQL would have to reproduce it and would drift.
 */
@Slf4j
@Component
@Order(113)
@RequiredArgsConstructor
public class ProgramBankAccountBackfillRunner implements ApplicationRunner {

    private final ProgramRepository programs;
    private final VirtualAccountRepository accounts;
    private final PhysicalAccountRepository banks;
    private final LegalEntityRepository entities;
    private final ShadowAccountService shadows;
    private final MarketProfileProperties marketProfile;
    private final com.bank.vam.service.treasury.HierarchyVaService hierarchy;
    private final com.bank.vam.service.treasury.BalanceAggregationServiceEnhanced aggregation;
    private final com.bank.vam.service.treasury.SettlementVaResolverService settlementAccounts;

    @Override
    public void run(ApplicationArguments args) {
        String bic = marketProfile.getActiveHomeBankBic();
        if (bic == null || bic.isBlank()) {
            return;
        }
        int opened = 0;
        int settlementsProvisioned = 0;
        java.util.Set<UUID> touched = new java.util.HashSet<>();
        int settlementsMoved = 0;
        for (Program program : programs.findAll()) {
            if (program.getStatus() == Program.ProgramStatus.CLOSED) {
                continue;
            }
            for (Map.Entry<String, BigDecimal> held : heldByCurrency(program).entrySet()) {
                String ccy = held.getKey();
                boolean hasBank = accounts.findByProgramIdAndAccountCategory(program.getId(), AccountCategory.PHYSICAL_MIRROR)
                        .stream().anyMatch(m -> ccy.equals(m.getCurrencyCode()) && m.getStatus() == VirtualAccount.VaStatus.ACTIVE);
                try {
                    if (!hasBank) {
                        opened += open(program, ccy, held.getValue(), bic) ? 1 : 0;
                    }
                    settlementsProvisioned += provisionSettlementIfMissing(program, ccy) ? 1 : 0;
                    settlementsMoved += freeSettlementsFromMirrors(program, ccy);
                    if (completeMirrorLayer(program, ccy)) {
                        touched.add(program.getCorporateId());
                    }
                } catch (Exception e) {
                    // One program's data must not stop the others, nor the application from starting.
                    log.error("Program bank accounts: could not open {} for program {}; continuing", ccy, program.getProgramCode(), e);
                }
            }
        }
        // Balances along the new mirror paths, now rather than at the next scheduled run: the funds check reads them.
        touched.forEach(aggregation::aggregateForCorporate);
        if (settlementsMoved > 0) {
            log.info("Program bank accounts: moved {} settlement accounts out of currency mirrors to their program's top level", settlementsMoved);
        }
        if (opened > 0 || settlementsProvisioned > 0) {
            log.info("Program bank accounts: opened {} home-bank accounts and {} settlement accounts for programs that had none in a currency they hold",
                    opened, settlementsProvisioned);
        }
    }

    /**
     * A payment also clears through the program's settlement account in its currency (a transaction account with the
     * settlement mark), which program setup provisions; one program in the demo data never had it.
     */
    private boolean provisionSettlementIfMissing(Program program, String ccy) {
        var inCcy = accounts.findByProgramIdAndAccountCategory(program.getId(), AccountCategory.TRANSACTION).stream()
                .filter(v -> ccy.equals(v.getCurrencyCode()) && v.getStatus() == VirtualAccount.VaStatus.ACTIVE).toList();
        if (inCcy.isEmpty() || inCcy.stream().anyMatch(v -> Boolean.TRUE.equals(v.getSettlementMark()))) {
            return false;
        }
        // At the program's top level, where the resolver finds it for every account of the program.
        VirtualAccount settlement = settlementAccounts.provisionSettlementVa(program.getId(), ccy);
        log.info("Program bank accounts: provisioned settlement account {} for program {} in {}", settlement.getVaNumber(),
                program.getProgramCode(), ccy);
        return true;
    }

    /**
     * A level that has a currency mirror counts only its mirrors, no longer the levels below it. Accounts opened before
     * mirrors were created with every new account sit in branches without any; once one appears higher up (for
     * instance when a settlement account is provisioned at the program's top level), the root counts nothing from
     * those branches and the funds check refuses their payments. So where the program has a mirror in the currency,
     * each of its accounts in it gets the mirrors its path is missing, as a new account would. Returns whether any
     * were created.
     */
    private boolean completeMirrorLayer(Program program, String ccy) {
        boolean layered = accounts.findByProgramIdAndAccountCategory(program.getId(), AccountCategory.CURRENCY_MIRROR).stream()
                .anyMatch(m -> ccy.equals(m.getCurrencyCode()));
        if (!layered) {
            return false;
        }
        int created = 0;
        for (VirtualAccount v : accounts.findByProgramIdAndAccountCategory(program.getId(), AccountCategory.TRANSACTION)) {
            if (ccy.equals(v.getCurrencyCode()) && v.getStatus() == VirtualAccount.VaStatus.ACTIVE) {
                created += hierarchy.propagateCurrencyMirrorsUp(v, program.getId()).size();
            }
        }
        if (created > 0) {
            log.info("Program bank accounts: added {} currency mirrors along the paths of program {}'s {} accounts", created,
                    program.getProgramCode(), ccy);
        }
        return created > 0;
    }

    /**
     * Settlement accounts provisioned inside a currency mirror (where provisioning used to put them) are never found by
     * the resolver, which looks beside an account, beside its ancestors and at the program's top level. Moved to the
     * top level, as one provisioned now would be. Only empty ones with nothing under them are moved.
     */
    private int freeSettlementsFromMirrors(Program program, String ccy) {
        int moved = 0;
        for (VirtualAccount s : accounts.findByProgramIdAndAccountCategory(program.getId(), AccountCategory.TRANSACTION)) {
            if (!Boolean.TRUE.equals(s.getSettlementMark()) || !ccy.equals(s.getCurrencyCode()) || s.getParentAccountId() == null) {
                continue;
            }
            boolean inMirror = accounts.findById(s.getParentAccountId())
                    .map(p -> p.getAccountCategory() == AccountCategory.CURRENCY_MIRROR).orElse(false);
            boolean empty = (s.getCurrentBalance() == null || s.getCurrentBalance().signum() == 0)
                    && accounts.findByParentAccountId(s.getId()).isEmpty();
            if (inMirror && empty) {
                s.setParentAccountId(null);
                s.setHierarchyLevel(0);
                accounts.save(s);
                log.info("Program bank accounts: moved settlement account {} of program {} to its top level", s.getVaNumber(),
                        program.getProgramCode());
                moved++;
            }
        }
        return moved;
    }

    /** Currency to the total of the program's active transaction accounts in it. */
    private Map<String, BigDecimal> heldByCurrency(Program program) {
        return accounts.findByProgramIdAndAccountCategory(program.getId(), AccountCategory.TRANSACTION).stream()
                .filter(v -> v.getStatus() == VirtualAccount.VaStatus.ACTIVE && v.getCurrencyCode() != null)
                .collect(Collectors.groupingBy(VirtualAccount::getCurrencyCode, TreeMap::new,
                        Collectors.reducing(BigDecimal.ZERO, v -> v.getCurrentBalance() == null ? BigDecimal.ZERO : v.getCurrentBalance(), BigDecimal::add)));
    }

    private boolean open(Program program, String ccy, BigDecimal opening, String bic) {
        LegalEntity owner = mainEntity(program, ccy);
        String number = number(program, ccy);
        PhysicalAccount pa = banks.save(PhysicalAccount.builder()
                .corporateId(program.getCorporateId())
                .legalEntityId(owner == null ? null : owner.getId())
                .accountNumber(number)
                .accountName(program.getProgramName() + " " + ccy + " settlement account")
                .accountType(PhysicalAccount.AccountType.CURRENT)
                .bankName(marketProfile.getActiveHomeBankName())
                .bankCode(bic)
                .bankCountry(marketProfile.getActiveIbanCountryCode() != null ? marketProfile.getActiveIbanCountryCode() : "GB")
                .bankRelationship(PhysicalAccount.BankRelationship.INTERNAL)
                .dataSource(PhysicalAccount.DataSource.CORE_BANKING)
                .entityName(owner == null ? null : owner.getEntityName())
                .entityCode(owner == null ? null : owner.getEntityCode())
                .currencyCode(ccy)
                .currentBalance(opening)
                .availableBalance(opening)
                .ledgerBalance(opening)
                .interestType(PhysicalAccount.InterestType.CREDIT)
                .canViewBalance(true).canViewTransactions(true).canInitiatePayments(true).canReceiveTransfers(true)
                .canHostVirtualAccounts(true).poolingEligible(true).sweepEligible(true).poolingEnabled(false).sweepEnabled(false)
                .status(PhysicalAccount.AccountStatus.ACTIVE)
                .syncStatus(PhysicalAccount.SyncStatus.SYNCED)
                .syncFrequencyMinutes(0)
                .lastSyncAt(LocalDateTime.now())
                .balanceAsOf(LocalDateTime.now())
                .openedDate(LocalDate.now())
                .build());
        VirtualAccount mirror = shadows.createShadowAccountForProgram(pa.getId(), program.getId());
        // The mirror's ledger starts where the bank does, so the refresh finds nothing to reconcile.
        mirror.setCurrentBalance(opening);
        mirror.setAvailableBalance(opening);
        mirror.setBankBalance(opening);
        accounts.save(mirror);
        log.info("Program bank accounts: opened {} {} for program {} ({}), balance {}", ccy, number, program.getProgramCode(),
                owner == null ? "no entity" : owner.getEntityName(), opening);
        return true;
    }

    /** The entity owning the program's root, else the one owning most of its accounts in the currency. */
    private LegalEntity mainEntity(Program program, String ccy) {
        UUID id = accounts.findByProgramIdAndAccountCategory(program.getId(), AccountCategory.ROOT).stream()
                .map(VirtualAccount::getOwningEntityId).filter(Objects::nonNull).findFirst()
                .orElseGet(() -> accounts.findByProgramIdAndAccountCategory(program.getId(), AccountCategory.TRANSACTION).stream()
                        .filter(v -> ccy.equals(v.getCurrencyCode()) && v.getOwningEntityId() != null)
                        .collect(Collectors.groupingBy(VirtualAccount::getOwningEntityId, Collectors.counting()))
                        .entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null));
        return id == null ? null : entities.findById(id).orElse(null);
    }

    /** A stable number per program and currency, moved on if another account already has it. */
    private String number(Program program, String ccy) {
        long n = Math.floorMod(program.getId().hashCode(), 90_000_000L) + 10_000_000L;
        String number = "PRG" + ccy + n;
        while (banks.existsByAccountNumber(number)) {
            number = "PRG" + ccy + (++n);
        }
        return number;
    }
}
