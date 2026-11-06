package com.bank.vam.fx.deal;

import com.bank.vam.config.MarketProfileProperties;
import com.bank.vam.entity.Corporate;
import com.bank.vam.entity.PhysicalAccount;
import com.bank.vam.entity.credit.CreditLimit;
import com.bank.vam.entity.hierarchy.LegalEntity;
import com.bank.vam.fx.HomeCurrency;
import com.bank.vam.repository.CorporateRepository;
import com.bank.vam.repository.PhysicalAccountRepository;
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
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Demo data: every entity holding an FX sub-limit gets a current account at the home bank, with its mirror, in its
 * functional currency, USD, EUR and GBP where it has none, opened with 5,000,000. Without them its deals have nowhere
 * to settle and are declined. Runs after {@link FxLineSeeder}; idempotent.
 *
 * <p>An entity that gains a second home-bank account in a currency (for instance a program's own bank account,
 * opened by {@code ProgramBankAccountBackfillRunner}, which runs before this) would have its deals in that currency
 * declined until someone chose between them. Where it has no instruction there, the instruction is set to the oldest
 * account: the one its deals were settling through while it was the only one.
 */
@Slf4j
@Component
@Order(114)
@RequiredArgsConstructor
public class FxSettlementAccountSeeder implements ApplicationRunner {

    private static final BigDecimal OPENING = new BigDecimal("5000000.00");

    private final CorporateRepository corporates;
    private final FxCreditLineRepository lines;
    private final LegalEntityRepository entities;
    private final FxSettlementAccounts settlementAccounts;
    private final PhysicalAccountRepository banks;
    private final ShadowAccountService shadows;
    private final MarketProfileProperties marketProfile;
    private final HomeCurrency homeCurrency;

    @Override
    public void run(ApplicationArguments args) {
        String bic = marketProfile.getActiveHomeBankBic();
        if (bic == null || bic.length() < 8) {
            return;
        }
        int opened = 0;
        for (Corporate c : corporates.findAll()) {
            for (CreditLimit line : lines.findLines(c.getId())) {
                for (CreditLimit sub : lines.findAllocations(line.getId())) {
                    try {
                        LegalEntity e = entities.findById(sub.getTargetId()).orElse(null);
                        if (e == null) {
                            continue;
                        }
                        Set<String> currencies = new LinkedHashSet<>();
                        currencies.add(e.getFunctionalCurrency() != null ? e.getFunctionalCurrency() : homeCurrency.of(c.getId()));
                        currencies.addAll(java.util.List.of("USD", "EUR", "GBP"));
                        for (String ccy : currencies) {
                            var eligible = settlementAccounts.eligible(e.getId(), ccy);
                            if (eligible.isEmpty()) {
                                shadows.ensureHomeBankShadow(banks.save(account(c, e, ccy, bic)));
                                opened++;
                            } else if (eligible.size() > 1 && !settlementAccounts.resolve(c.getId(), e.getId(), ccy).fromInstruction()) {
                                settlementAccounts.setInstruction(c.getId(), e.getId(), ccy, eligible.get(0).id());
                                log.info("FX settlement accounts: {} now has {} {} accounts at the home bank; its deals keep settling "
                                        + "through {}", e.getEntityName(), eligible.size(), ccy, eligible.get(0).label());
                            }
                        }
                    } catch (Exception ex) {
                        // One entity's data must not stop the others, nor the application from starting.
                        log.error("FX settlement accounts: seeding failed for entity {}; continuing", sub.getTargetId(), ex);
                    }
                }
            }
        }
        if (opened > 0) {
            log.info("FX settlement accounts: opened {} home-bank accounts for entities with FX sub-limits", opened);
        }
    }

    private PhysicalAccount account(Corporate c, LegalEntity e, String ccy, String bic) {
        String number = number(e, ccy);
        String country = marketProfile.getActiveIbanCountryCode();
        return PhysicalAccount.builder()
                .corporateId(c.getId())
                .legalEntityId(e.getId())
                .accountNumber(number)
                .accountName(e.getEntityName() + " " + ccy + " current account")
                .accountType(PhysicalAccount.AccountType.CURRENT)
                .bankName(marketProfile.getActiveHomeBankName())
                .bankCode(bic)
                .bankCountry(country != null ? country : "GB")
                .bankRelationship(PhysicalAccount.BankRelationship.INTERNAL)
                .dataSource(PhysicalAccount.DataSource.CORE_BANKING)
                .entityName(e.getEntityName())
                .entityCode(e.getEntityCode())
                .currencyCode(ccy)
                .currentBalance(OPENING)
                .availableBalance(OPENING)
                .ledgerBalance(OPENING)
                .interestType(PhysicalAccount.InterestType.CREDIT)
                .canViewBalance(true)
                .canViewTransactions(true)
                .canInitiatePayments(true)
                .canReceiveTransfers(true)
                .canHostVirtualAccounts(true)
                .poolingEligible(true)
                .sweepEligible(true)
                .poolingEnabled(false)
                .sweepEnabled(false)
                .status(PhysicalAccount.AccountStatus.ACTIVE)
                .syncStatus(PhysicalAccount.SyncStatus.SYNCED)
                .syncFrequencyMinutes(0)
                .lastSyncAt(LocalDateTime.now())
                .balanceAsOf(LocalDateTime.now())
                .openedDate(LocalDate.now())
                .build();
    }

    /** A stable number per entity and currency, moved on if another account already has it. */
    private String number(LegalEntity e, String ccy) {
        long n = Math.floorMod(e.getId().hashCode(), 90_000_000L) + 10_000_000L;
        String number = ccy + n;
        while (banks.existsByAccountNumber(number)) {
            number = ccy + (++n);
        }
        return number;
    }
}
