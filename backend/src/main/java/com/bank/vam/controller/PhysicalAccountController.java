package com.bank.vam.controller;

import com.bank.vam.entity.PhysicalAccount;
import com.bank.vam.entity.PhysicalAccount.AccountType;
import com.bank.vam.entity.PhysicalAccount.AccountStatus;
import com.bank.vam.entity.PhysicalAccount.SyncStatus;
import com.bank.vam.entity.PhysicalAccount.SweepRole;
import com.bank.vam.entity.PhysicalAccount.InterestType;
import com.bank.vam.entity.PhysicalAccount.BankRelationship;
import com.bank.vam.entity.PhysicalAccount.DataSource;
import com.bank.vam.entity.Corporate;
import com.bank.vam.entity.hierarchy.LegalEntity;
import com.bank.vam.repository.PhysicalAccountRepository;
import com.bank.vam.repository.CorporateRepository;
import com.bank.vam.repository.hierarchy.LegalEntityRepository;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.exception.BusinessException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * PhysicalAccountController - REST API for Physical Bank Account management.
 * 
 * UPDATED: Added proper Legal Entity integration with legalEntityId support.
 * 
 * Physical accounts are real bank accounts held at external banks, used for:
 * - Notional Pooling (interest optimization)
 * - Cash Concentration (physical sweeps)
 * - Settlement for Virtual Account operations
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/physical-accounts")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@Tag(name = "Physical Accounts", description = "Physical Bank Account Management APIs")
public class PhysicalAccountController {

    private final PhysicalAccountRepository physicalAccountRepository;
    private final CorporateRepository corporateRepository;
    private final LegalEntityRepository legalEntityRepository;
    private final com.bank.vam.config.MarketProfileProperties marketProfile;
    private final com.bank.vam.config.HomeBankProperties homeBank;

    // ========================================================================
    // STATS & SUMMARY
    // ========================================================================

    @GetMapping("/stats")
    @Operation(summary = "Get physical accounts statistics")
    public ResponseEntity<Map<String, Object>> getStats(
            @RequestParam(required = false) UUID corporateId,
            @RequestParam(required = false) UUID legalEntityId) {
        
        List<PhysicalAccount> accounts;
        if (legalEntityId != null) {
            accounts = physicalAccountRepository.findByLegalEntityId(legalEntityId);
        } else if (corporateId != null) {
            accounts = physicalAccountRepository.findByCorporateId(corporateId);
        } else {
            accounts = physicalAccountRepository.findAll();
        }
        
        long total = accounts.size();
        long active = accounts.stream().filter(a -> a.getStatus() == AccountStatus.ACTIVE).count();
        long dormant = accounts.stream().filter(a -> a.getStatus() == AccountStatus.DORMANT).count();
        long poolingEnabled = accounts.stream().filter(a -> Boolean.TRUE.equals(a.getPoolingEnabled())).count();
        long sweepEnabled = accounts.stream().filter(a -> Boolean.TRUE.equals(a.getSweepEnabled())).count();

        // FX-honesty: balances are summed PER CURRENCY, not cross-currency. The
        // legacy `totalBalance` field below stays in the response for backward
        // compatibility (consumers that don't care about FX rigour), but it
        // now reflects the numeric sum scoped to the dominant currency — not
        // a meaningless mixed-currency cross-add labelled with the system
        // base.
        Map<String, BigDecimal> balancesByCurrency = accounts.stream()
                .filter(a -> a.getCurrencyCode() != null && a.getCurrentBalance() != null)
                .collect(Collectors.groupingBy(
                        PhysicalAccount::getCurrencyCode,
                        LinkedHashMap::new,
                        Collectors.reducing(BigDecimal.ZERO,
                                PhysicalAccount::getCurrentBalance,
                                BigDecimal::add)));

        Map<String, BigDecimal> availableByCurrency = accounts.stream()
                .filter(a -> a.getCurrencyCode() != null && a.getAvailableBalance() != null)
                .collect(Collectors.groupingBy(
                        PhysicalAccount::getCurrencyCode,
                        LinkedHashMap::new,
                        Collectors.reducing(BigDecimal.ZERO,
                                PhysicalAccount::getAvailableBalance,
                                BigDecimal::add)));

        // Dominant currency = the one with the largest aggregate balance.
        // For a single-currency corporate (e.g. Mercator with 4 EUR accounts)
        // this is the only currency; for multi-currency portfolios it's the
        // "headline" figure to render dominant in the hero, with the rest
        // shown as per-currency chips.
        String dominantCurrency = balancesByCurrency.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);

        BigDecimal totalBalance = dominantCurrency == null
                ? BigDecimal.ZERO
                : balancesByCurrency.get(dominantCurrency);

        BigDecimal totalAvailable = dominantCurrency == null
                ? BigDecimal.ZERO
                : availableByCurrency.getOrDefault(dominantCurrency, BigDecimal.ZERO);

        long bankCount = accounts.stream()
                .map(PhysicalAccount::getBankCode)
                .filter(Objects::nonNull)
                .distinct()
                .count();

        List<String> currencies = new ArrayList<>(balancesByCurrency.keySet());

        long synced = accounts.stream().filter(a -> a.getSyncStatus() == SyncStatus.SYNCED).count();
        long pending = accounts.stream().filter(a -> a.getSyncStatus() == SyncStatus.PENDING).count();
        long errors = accounts.stream().filter(a -> a.getSyncStatus() == SyncStatus.ERROR).count();

        // Home / External counts derived AUTHORITATIVELY from the configured
        // home-bank BIC (`vam.home-bank.bic`), not from the per-account
        // `bank_relationship` field — that field defaults to INTERNAL in
        // seeded data and made every account look home-bank-held (see the
        // long-standing comment on `getBankSummaries` at line ~172).
        //
        // An account is "home bank" iff its `bankCode` (SWIFT/BIC) matches
        // the configured BIC, case-insensitive. Everything else is external.
        String configuredHomeBic = homeBank.getBic();
        long homeBankCount = accounts.stream()
                .filter(a -> configuredHomeBic != null
                          && a.getBankCode() != null
                          && configuredHomeBic.equalsIgnoreCase(a.getBankCode()))
                .count();
        long externalBankCount = accounts.size() - homeBankCount;

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", total);
        stats.put("active", active);
        stats.put("dormant", dormant);
        stats.put("poolingEnabled", poolingEnabled);
        stats.put("sweepEnabled", sweepEnabled);
        // Legacy single-figure totals — represent the DOMINANT currency only.
        stats.put("totalBalance", totalBalance);
        stats.put("totalAvailableBalance", totalAvailable);
        // FX-honest per-currency breakdown — the canonical source the
        // frontend should render from going forward.
        stats.put("balancesByCurrency", balancesByCurrency);
        stats.put("availableByCurrency", availableByCurrency);
        stats.put("dominantCurrency", dominantCurrency);
        stats.put("bankCount", bankCount);
        stats.put("currencies", currencies);
        stats.put("syncedCount", synced);
        stats.put("pendingCount", pending);
        stats.put("errorCount", errors);
        stats.put("homeBankCount", homeBankCount);
        stats.put("externalBankCount", externalBankCount);
        // Echo the BIC used for the home/external split so the frontend can
        // surface it in tooltips ("home bank: ENBD / Emirates NBD").
        stats.put("homeBankBic", configuredHomeBic);
        // Home-bank display name derived from the actual account data by BIC
        // (same predicate as the home/external split above) — never a static
        // config string, so it stays consistent with the configured BIC.
        // Falls back to the BIC itself when no account carries that bank.
        String resolvedHomeBankName = accounts.stream()
                .filter(a -> configuredHomeBic != null
                          && a.getBankCode() != null
                          && configuredHomeBic.equalsIgnoreCase(a.getBankCode()))
                .map(a -> a.getBankName())
                .filter(n -> n != null)
                .findFirst()
                .orElse(configuredHomeBic);
        stats.put("homeBankName", resolvedHomeBankName);

        return ResponseEntity.ok(Map.of("success", true, "data", stats));
    }

    @GetMapping("/banks")
    @Operation(summary = "Get bank summaries with account counts and balances")
    public ResponseEntity<Map<String, Object>> getBankSummaries(
            @RequestParam(required = false) UUID corporateId) {
        
        List<PhysicalAccount> accounts;
        if (corporateId != null) {
            accounts = physicalAccountRepository.findByCorporateId(corporateId);
        } else {
            accounts = physicalAccountRepository.findAll();
        }
        
        Map<String, List<PhysicalAccount>> byBank = accounts.stream()
                .filter(a -> a.getBankCode() != null)
                .collect(Collectors.groupingBy(PhysicalAccount::getBankCode));
        
        List<Map<String, Object>> bankSummaries = new ArrayList<>();
        
        for (Map.Entry<String, List<PhysicalAccount>> entry : byBank.entrySet()) {
            List<PhysicalAccount> bankAccounts = entry.getValue();
            PhysicalAccount first = bankAccounts.get(0);
            
            BigDecimal totalBalance = bankAccounts.stream()
                    .map(PhysicalAccount::getCurrentBalance)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            
            List<String> currencies = bankAccounts.stream()
                    .map(PhysicalAccount::getCurrencyCode)
                    .distinct()
                    .collect(Collectors.toList());
            
            int vaHostCount = (int) bankAccounts.stream()
                    .filter(a -> Boolean.TRUE.equals(a.getCanHostVirtualAccounts()))
                    .count();
            
            // "Home Bank" is a single bank per deployment, identified by BIC
            // in HomeBankProperties. Don't trust the per-account
            // `bankRelationship` field for this — seeded data can default every
            // account to INTERNAL, which makes every bank look like the home
            // bank in the UI. Derive it authoritatively from the configured BIC.
            // PhysicalAccount.bankCode is the SWIFT/BIC (see entity).
            String configuredHomeBic = homeBank.getBic();
            String thisBankBic = first.getBankCode();
            boolean isHomeBank = configuredHomeBic != null
                    && thisBankBic != null
                    && configuredHomeBic.equalsIgnoreCase(thisBankBic);

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("bankCode", entry.getKey());
            summary.put("bankName", first.getBankName());
            summary.put("bankCountry", first.getBankCountry());
            summary.put("bankRelationship", isHomeBank ? "INTERNAL" : "EXTERNAL");
            summary.put("isHomeBank", isHomeBank);
            summary.put("accountCount", bankAccounts.size());
            summary.put("totalBalance", totalBalance);
            summary.put("currencies", currencies);
            summary.put("vaHostCount", vaHostCount);

            bankSummaries.add(summary);
        }

        bankSummaries.sort((a, b) -> {
            // Home bank first, then by total balance descending.
            boolean aHome = Boolean.TRUE.equals(a.get("isHomeBank"));
            boolean bHome = Boolean.TRUE.equals(b.get("isHomeBank"));
            if (aHome != bHome) return aHome ? -1 : 1;
            BigDecimal balA = (BigDecimal) a.get("totalBalance");
            BigDecimal balB = (BigDecimal) b.get("totalBalance");
            return balB.compareTo(balA);
        });
        
        return ResponseEntity.ok(Map.of("success", true, "data", bankSummaries));
    }

    // ========================================================================
    // LIST & SEARCH
    // ========================================================================

    @GetMapping
    @Operation(summary = "Get all physical accounts with filters")
    public ResponseEntity<Map<String, Object>> getAllAccounts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) UUID corporateId,
            @RequestParam(required = false) UUID legalEntityId,
            @RequestParam(required = false) String bankCode,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String bankRelationship,
            @RequestParam(required = false) Boolean poolingEnabled,
            @RequestParam(required = false) Boolean sweepEnabled,
            @RequestParam(required = false) String query) {
        
        List<PhysicalAccount> accounts = physicalAccountRepository.findAll(
                Sort.by("createdAt").descending());
        
        // Apply filters
        if (corporateId != null) {
            accounts = accounts.stream()
                    .filter(a -> corporateId.equals(a.getCorporateId()))
                    .collect(Collectors.toList());
        }
        
        if (legalEntityId != null) {
            accounts = accounts.stream()
                    .filter(a -> legalEntityId.equals(a.getLegalEntityId()))
                    .collect(Collectors.toList());
        }

        if (bankCode != null && !bankCode.isEmpty()) {
            accounts = accounts.stream()
                    .filter(a -> bankCode.equals(a.getBankCode()))
                    .collect(Collectors.toList());
        }
        if (currency != null && !currency.isEmpty()) {
            accounts = accounts.stream()
                    .filter(a -> currency.equals(a.getCurrencyCode()))
                    .collect(Collectors.toList());
        }
        if (status != null && !status.isEmpty()) {
            try {
                AccountStatus statusEnum = AccountStatus.valueOf(status.toUpperCase());
                accounts = accounts.stream()
                        .filter(a -> a.getStatus() == statusEnum)
                        .collect(Collectors.toList());
            } catch (IllegalArgumentException ignored) {}
        }
        if (bankRelationship != null && !bankRelationship.isEmpty()) {
            try {
                BankRelationship relationshipEnum = BankRelationship.valueOf(bankRelationship.toUpperCase());
                accounts = accounts.stream()
                        .filter(a -> a.getBankRelationship() == relationshipEnum)
                        .collect(Collectors.toList());
            } catch (IllegalArgumentException ignored) {}
        }
        if (poolingEnabled != null) {
            accounts = accounts.stream()
                    .filter(a -> poolingEnabled.equals(a.getPoolingEnabled()))
                    .collect(Collectors.toList());
        }
        if (sweepEnabled != null) {
            accounts = accounts.stream()
                    .filter(a -> sweepEnabled.equals(a.getSweepEnabled()))
                    .collect(Collectors.toList());
        }
        if (query != null && !query.isEmpty()) {
            String q = query.toLowerCase();
            accounts = accounts.stream()
                    .filter(a -> 
                            (a.getAccountName() != null && a.getAccountName().toLowerCase().contains(q)) ||
                            (a.getAccountNumber() != null && a.getAccountNumber().toLowerCase().contains(q)) ||
                            (a.getIban() != null && a.getIban().toLowerCase().contains(q)) ||
                            (a.getEntityName() != null && a.getEntityName().toLowerCase().contains(q)) ||
                            (a.getBankName() != null && a.getBankName().toLowerCase().contains(q)))
                    .collect(Collectors.toList());
        }
        
        // Paginate
        int totalElements = accounts.size();
        int start = page * size;
        int end = Math.min(start + size, totalElements);
        List<PhysicalAccount> pageAccounts = start < totalElements ? 
                accounts.subList(start, end) : Collections.emptyList();
        
        List<Map<String, Object>> content = pageAccounts.stream()
                .map(this::toAccountResponse)
                .collect(Collectors.toList());
        
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("content", content);
        response.put("page", page);
        response.put("pageSize", size);
        response.put("totalElements", totalElements);
        response.put("totalPages", (int) Math.ceil((double) totalElements / size));
        
        return ResponseEntity.ok(Map.of("success", true, "data", response));
    }

    // ========================================================================
    // CRUD OPERATIONS
    // ========================================================================

    @GetMapping("/{id}")
    @Operation(summary = "Get physical account by ID")
    public ResponseEntity<Map<String, Object>> getAccountById(@PathVariable UUID id) {
        PhysicalAccount account = physicalAccountRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Physical account not found: " + id));
        
        return ResponseEntity.ok(Map.of("success", true, "data", toAccountDetailResponse(account)));
    }

    @GetMapping("/number/{accountNumber}")
    @Operation(summary = "Get physical account by account number")
    public ResponseEntity<Map<String, Object>> getAccountByNumber(@PathVariable String accountNumber) {
        PhysicalAccount account = physicalAccountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Physical account not found: " + accountNumber));
        
        return ResponseEntity.ok(Map.of("success", true, "data", toAccountDetailResponse(account)));
    }

    @PostMapping
    @Operation(summary = "Create a new physical account")
    public ResponseEntity<Map<String, Object>> createAccount(@RequestBody CreateAccountRequest request) {
        log.info("Creating physical account: {}", request);
        
        // Validate and lookup corporate
        UUID corporateId = request.corporateId;
        if (corporateId == null) {
            throw new BusinessException("Corporate ID is required");
        }
        
        Corporate corporate = corporateRepository.findById(corporateId)
                .orElseThrow(() -> new ResourceNotFoundException("Corporate not found: " + corporateId));
        
        // Lookup legal entity if provided
        LegalEntity legalEntity = null;
        String entityName = request.entityName;
        String entityCode = request.entityCode;
        String currencyCode = request.currencyCode != null ? request.currencyCode : marketProfile.getDefaultCurrency();
        
        if (request.legalEntityId != null) {
            legalEntity = legalEntityRepository.findById(request.legalEntityId)
                    .orElseThrow(() -> new ResourceNotFoundException("Legal entity not found: " + request.legalEntityId));
            
            // Validate entity belongs to corporate
            if (!corporateId.equals(legalEntity.getCorporateId())) {
                throw new BusinessException("Legal entity does not belong to the specified corporate");
            }
            
            // Validate entity can hold physical accounts
            if (!Boolean.TRUE.equals(legalEntity.getCanHoldPhysicalAccounts())) {
                throw new BusinessException("Legal entity is not configured to hold physical accounts: " + legalEntity.getEntityCode());
            }
            
            // Inherit entity details
            entityName = legalEntity.getEntityName();
            entityCode = legalEntity.getEntityCode();
            
            // Use entity's functional currency if not specified
            if (request.currencyCode == null || request.currencyCode.isEmpty()) {
                currencyCode = legalEntity.getFunctionalCurrency();
            }
            
            log.info("Legal entity found: {} - {}", legalEntity.getEntityCode(), legalEntity.getEntityName());
        }
        
        // Check for duplicate account number
        if (request.accountNumber != null && 
            physicalAccountRepository.findByAccountNumber(request.accountNumber).isPresent()) {
            throw new BusinessException("Account number already exists: " + request.accountNumber);
        }
        
        // Check for duplicate IBAN
        if (request.iban != null && !request.iban.isEmpty() &&
            physicalAccountRepository.findByIban(request.iban).isPresent()) {
            throw new BusinessException("IBAN already exists: " + request.iban);
        }
        
        // Determine bank relationship and data source
        BankRelationship bankRelationship = BankRelationship.INTERNAL;
        DataSource dataSource = DataSource.CORE_BANKING;
        
        if (request.bankRelationship != null) {
            try {
                bankRelationship = BankRelationship.valueOf(request.bankRelationship.toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("Invalid bank relationship: {}, defaulting to INTERNAL", request.bankRelationship);
            }
        }
        
        if (request.dataSource != null) {
            try {
                dataSource = DataSource.valueOf(request.dataSource.toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("Invalid data source: {}, defaulting to CORE_BANKING", request.dataSource);
            }
        }
        
        // Set capabilities based on bank relationship
        boolean isInternal = bankRelationship == BankRelationship.INTERNAL;
        
        PhysicalAccount account = PhysicalAccount.builder()
                .corporateId(corporateId)
                .legalEntityId(legalEntity != null ? legalEntity.getId() : null)
                .accountNumber(request.accountNumber)
                .iban(request.iban)
                .accountName(request.accountName)
                .accountType(request.accountType != null ? 
                        AccountType.valueOf(request.accountType.toUpperCase()) : AccountType.CURRENT)
                .bankName(request.bankName)
                .bankCode(request.bankCode)
                .bankCountry(request.bankCountry != null ? request.bankCountry : "AE")
                .branchName(request.branchName)
                .branchCode(request.branchCode)
                .bankRelationship(bankRelationship)
                .dataSource(dataSource)
                .entityName(entityName)
                .entityCode(entityCode)
                .currencyCode(currencyCode)
                .currentBalance(request.initialBalance != null ? request.initialBalance : BigDecimal.ZERO)
                .availableBalance(request.initialBalance != null ? request.initialBalance : BigDecimal.ZERO)
                .ledgerBalance(request.initialBalance != null ? request.initialBalance : BigDecimal.ZERO)
                .interestRate(request.interestRate)
                .interestType(request.interestType != null ? InterestType.valueOf(request.interestType.toUpperCase()) : InterestType.CREDIT)
                // Capabilities based on bank relationship
                .canViewBalance(true)
                .canViewTransactions(true)
                .canInitiatePayments(isInternal)
                .canReceiveTransfers(isInternal)
                .canHostVirtualAccounts(isInternal)
                .poolingEligible(isInternal)
                .sweepEligible(isInternal)
                .poolingEnabled(false)
                .sweepEnabled(false)
                .status(AccountStatus.ACTIVE)
                .syncStatus(SyncStatus.SYNCED)
                .syncFrequencyMinutes(isInternal ? 0 : 60)
                .lastSyncAt(LocalDateTime.now())
                .balanceAsOf(LocalDateTime.now())
                .openedDate(LocalDate.now())
                .relationshipManager(request.relationshipManager)
                .build();
        
        PhysicalAccount saved = physicalAccountRepository.save(account);
        log.info("Created physical account: {} at {} for entity {}", 
                saved.getAccountNumber(), saved.getBankName(), saved.getEntityCode());
        
        return ResponseEntity.ok(Map.of(
                "success", true, 
                "data", toAccountResponse(saved),
                "message", "Physical account created successfully"
        ));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update physical account")
    public ResponseEntity<Map<String, Object>> updateAccount(
            @PathVariable UUID id,
            @RequestBody UpdateAccountRequest request) {
        
        PhysicalAccount account = physicalAccountRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Physical account not found: " + id));
        
        if (request.accountName != null) account.setAccountName(request.accountName);
        if (request.branchName != null) account.setBranchName(request.branchName);
        if (request.branchCode != null) account.setBranchCode(request.branchCode);
        if (request.interestRate != null) account.setInterestRate(request.interestRate);
        if (request.relationshipManager != null) account.setRelationshipManager(request.relationshipManager);
        
        // Update legal entity if provided
        if (request.legalEntityId != null) {
            LegalEntity legalEntity = legalEntityRepository.findById(request.legalEntityId)
                    .orElseThrow(() -> new ResourceNotFoundException("Legal entity not found: " + request.legalEntityId));
            
            account.setLegalEntityId(legalEntity.getId());
            account.setEntityName(legalEntity.getEntityName());
            account.setEntityCode(legalEntity.getEntityCode());
        } else if (request.entityName != null || request.entityCode != null) {
            // Manual override
            if (request.entityName != null) account.setEntityName(request.entityName);
            if (request.entityCode != null) account.setEntityCode(request.entityCode);
        }
        
        PhysicalAccount saved = physicalAccountRepository.save(account);
        log.info("Updated physical account: {}", saved.getAccountNumber());
        
        return ResponseEntity.ok(Map.of(
                "success", true, 
                "data", toAccountResponse(saved),
                "message", "Physical account updated successfully"
        ));
    }

    // ========================================================================
    // STATUS MANAGEMENT
    // ========================================================================

    @PostMapping("/{id}/status")
    @Operation(summary = "Update account status")
    public ResponseEntity<Map<String, Object>> updateStatus(
            @PathVariable UUID id,
            @RequestBody StatusUpdateRequest request) {
        
        PhysicalAccount account = physicalAccountRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Physical account not found: " + id));
        
        AccountStatus newStatus = AccountStatus.valueOf(request.status.toUpperCase());
        account.setStatus(newStatus);
        
        if (newStatus == AccountStatus.CLOSED) {
            account.setClosedDate(LocalDate.now());
        }
        
        PhysicalAccount saved = physicalAccountRepository.save(account);
        log.info("Updated status of {} to {}", account.getAccountNumber(), newStatus);
        
        return ResponseEntity.ok(Map.of(
                "success", true, 
                "data", toAccountResponse(saved),
                "message", "Account status updated to " + newStatus
        ));
    }

    // ========================================================================
    // BALANCE OPERATIONS
    // ========================================================================

    @GetMapping("/{id}/balance")
    @Operation(summary = "Get account balance")
    public ResponseEntity<Map<String, Object>> getBalance(@PathVariable UUID id) {
        PhysicalAccount account = physicalAccountRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Physical account not found: " + id));
        
        Map<String, Object> balance = new LinkedHashMap<>();
        balance.put("accountId", account.getId());
        balance.put("accountNumber", account.getAccountNumber());
        balance.put("accountName", account.getAccountName());
        balance.put("currency", account.getCurrencyCode());
        balance.put("currentBalance", account.getCurrentBalance());
        balance.put("availableBalance", account.getAvailableBalance());
        balance.put("ledgerBalance", account.getLedgerBalance());
        balance.put("syncStatus", account.getSyncStatus());
        balance.put("lastSyncAt", account.getLastSyncAt());
        
        return ResponseEntity.ok(Map.of("success", true, "data", balance));
    }

    @PostMapping("/{id}/sync")
    @Operation(summary = "Sync account balance from bank")
    public ResponseEntity<Map<String, Object>> syncBalance(@PathVariable UUID id) {
        PhysicalAccount account = physicalAccountRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Physical account not found: " + id));
        
        // In production, this would call the bank API (BANCS)
        account.setSyncStatus(SyncStatus.SYNCED);
        account.setLastSyncAt(LocalDateTime.now());
        account.setBalanceAsOf(LocalDateTime.now());
        account.setSyncErrorMessage(null);
        
        PhysicalAccount saved = physicalAccountRepository.save(account);
        log.info("Synced balance for account: {}", account.getAccountNumber());
        
        return ResponseEntity.ok(Map.of(
                "success", true, 
                "data", toAccountResponse(saved),
                "message", "Account synced successfully"
        ));
    }

    @PostMapping("/sync-all")
    @Operation(summary = "Sync all accounts")
    public ResponseEntity<Map<String, Object>> syncAllAccounts(
            @RequestParam(required = false) UUID corporateId) {
        
        List<PhysicalAccount> accounts;
        if (corporateId != null) {
            accounts = physicalAccountRepository.findByCorporateId(corporateId);
        } else {
            accounts = physicalAccountRepository.findAll();
        }
        
        int synced = 0;
        for (PhysicalAccount account : accounts) {
            if (account.getStatus() == AccountStatus.ACTIVE) {
                account.setSyncStatus(SyncStatus.SYNCED);
                account.setLastSyncAt(LocalDateTime.now());
                account.setBalanceAsOf(LocalDateTime.now());
                physicalAccountRepository.save(account);
                synced++;
            }
        }
        
        log.info("Synced {} accounts", synced);
        
        return ResponseEntity.ok(Map.of(
                "success", true, 
                "data", Map.of("syncedCount", synced, "totalCount", accounts.size()),
                "message", "Synced " + synced + " accounts"
        ));
    }

    // ========================================================================
    // TREASURY CONFIGURATION
    // ========================================================================

    @PostMapping("/{id}/pooling/enable")
    @Operation(summary = "Enable notional pooling for account")
    public ResponseEntity<Map<String, Object>> enablePooling(
            @PathVariable UUID id,
            @RequestBody PoolingConfigRequest request) {
        
        PhysicalAccount account = physicalAccountRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Physical account not found: " + id));
        
        account.enablePooling(request.poolId, request.poolReference);
        PhysicalAccount saved = physicalAccountRepository.save(account);
        
        log.info("Enabled pooling for account: {} - pool: {}", account.getAccountNumber(), request.poolReference);
        
        return ResponseEntity.ok(Map.of(
                "success", true, 
                "data", toAccountResponse(saved),
                "message", "Pooling enabled for account"
        ));
    }

    @PostMapping("/{id}/pooling/disable")
    @Operation(summary = "Disable notional pooling for account")
    public ResponseEntity<Map<String, Object>> disablePooling(@PathVariable UUID id) {
        PhysicalAccount account = physicalAccountRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Physical account not found: " + id));
        
        account.disablePooling();
        PhysicalAccount saved = physicalAccountRepository.save(account);
        
        log.info("Disabled pooling for account: {}", account.getAccountNumber());
        
        return ResponseEntity.ok(Map.of(
                "success", true, 
                "data", toAccountResponse(saved),
                "message", "Pooling disabled for account"
        ));
    }

    @PostMapping("/{id}/sweep/enable")
    @Operation(summary = "Enable cash sweep for account")
    public ResponseEntity<Map<String, Object>> enableSweep(
            @PathVariable UUID id,
            @RequestBody SweepConfigRequest request) {
        
        PhysicalAccount account = physicalAccountRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Physical account not found: " + id));
        
        if ("HEADER".equalsIgnoreCase(request.role)) {
            account.enableSweepAsHeader(request.sweepRuleId);
        } else {
            account.enableSweepAsParticipant(request.sweepRuleId);
        }
        
        PhysicalAccount saved = physicalAccountRepository.save(account);
        log.info("Enabled sweep for account: {} as {}", account.getAccountNumber(), request.role);
        
        return ResponseEntity.ok(Map.of(
                "success", true, 
                "data", toAccountResponse(saved),
                "message", "Sweep enabled as " + request.role
        ));
    }

    @PostMapping("/{id}/sweep/disable")
    @Operation(summary = "Disable cash sweep for account")
    public ResponseEntity<Map<String, Object>> disableSweep(@PathVariable UUID id) {
        PhysicalAccount account = physicalAccountRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Physical account not found: " + id));
        
        account.disableSweep();
        PhysicalAccount saved = physicalAccountRepository.save(account);
        
        log.info("Disabled sweep for account: {}", account.getAccountNumber());
        
        return ResponseEntity.ok(Map.of(
                "success", true, 
                "data", toAccountResponse(saved),
                "message", "Sweep disabled for account"
        ));
    }

    // ========================================================================
    // LOOKUP DATA
    // ========================================================================

    @GetMapping("/account-types")
    @Operation(summary = "Get available account types")
    public ResponseEntity<Map<String, Object>> getAccountTypes() {
        List<Map<String, String>> types = Arrays.stream(AccountType.values())
                .map(t -> Map.of("code", t.name(), "name", formatEnumName(t.name())))
                .collect(Collectors.toList());
        return ResponseEntity.ok(Map.of("success", true, "data", types));
    }

    @GetMapping("/currencies")
    @Operation(summary = "Get currencies in use (optionally scoped by corporate / legal entity)")
    public ResponseEntity<Map<String, Object>> getCurrencies(
            @RequestParam(required = false) UUID corporateId,
            @RequestParam(required = false) UUID legalEntityId) {

        // Scope to the selected corporate / entity so the page's "Across N
        // currencies" chip row reflects only what the user is looking at —
        // not every currency in the entire database (which was the prior
        // behaviour and made e.g. Mercator's EUR-only view show 6 chips).
        List<PhysicalAccount> accounts;
        if (legalEntityId != null) {
            accounts = physicalAccountRepository.findByLegalEntityId(legalEntityId);
        } else if (corporateId != null) {
            accounts = physicalAccountRepository.findByCorporateId(corporateId);
        } else {
            accounts = physicalAccountRepository.findAll();
        }

        List<String> currencies = accounts.stream()
                .map(PhysicalAccount::getCurrencyCode)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        // Empty-scope fallback: only if there are NO accounts in the whole
        // system, suggest the active market's currency vocabulary so the
        // page's currency-filter dropdown isn't completely empty for fresh
        // installs. When the user has scoped to a corporate with no accounts
        // we return an empty list (the right answer) instead of muddying it
        // with market suggestions.
        if (currencies.isEmpty() && corporateId == null && legalEntityId == null) {
            currencies = marketProfile.getActiveSuggestedCurrencies();
        }

        return ResponseEntity.ok(Map.of("success", true, "data", currencies));
    }

    @GetMapping("/entity/{legalEntityId}")
    @Operation(summary = "Get physical accounts by legal entity")
    public ResponseEntity<Map<String, Object>> getAccountsByLegalEntity(
            @PathVariable UUID legalEntityId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status) {
        
        List<PhysicalAccount> accounts = physicalAccountRepository.findByLegalEntityId(legalEntityId);
        
        if (status != null && !status.isEmpty()) {
            try {
                AccountStatus statusEnum = AccountStatus.valueOf(status.toUpperCase());
                accounts = accounts.stream()
                        .filter(a -> a.getStatus() == statusEnum)
                        .collect(Collectors.toList());
            } catch (IllegalArgumentException ignored) {}
        }
        
        int totalElements = accounts.size();
        int start = page * size;
        int end = Math.min(start + size, totalElements);
        List<PhysicalAccount> pageAccounts = start < totalElements ? 
                accounts.subList(start, end) : Collections.emptyList();
        
        List<Map<String, Object>> content = pageAccounts.stream()
                .map(this::toAccountResponse)
                .collect(Collectors.toList());
        
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("content", content);
        response.put("page", page);
        response.put("pageSize", size);
        response.put("totalElements", totalElements);
        response.put("totalPages", (int) Math.ceil((double) totalElements / size));
        
        return ResponseEntity.ok(Map.of("success", true, "data", response));
    }

    @PostMapping("/entities")
    @Operation(summary = "Get physical accounts for multiple legal entities")
    public ResponseEntity<Map<String, Object>> getAccountsByLegalEntities(
            @RequestBody List<UUID> legalEntityIds,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        
        List<PhysicalAccount> accounts = physicalAccountRepository.findByLegalEntityIdIn(legalEntityIds);
        
        int totalElements = accounts.size();
        int start = page * size;
        int end = Math.min(start + size, totalElements);
        List<PhysicalAccount> pageAccounts = start < totalElements ? 
                accounts.subList(start, end) : Collections.emptyList();
        
        List<Map<String, Object>> content = pageAccounts.stream()
                .map(this::toAccountResponse)
                .collect(Collectors.toList());
        
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("content", content);
        response.put("page", page);
        response.put("pageSize", size);
        response.put("totalElements", totalElements);
        response.put("totalPages", (int) Math.ceil((double) totalElements / size));
        
        return ResponseEntity.ok(Map.of("success", true, "data", response));
    }

    // ========================================================================
    // PRIVATE HELPERS
    // ========================================================================

    private Map<String, Object> toAccountResponse(PhysicalAccount account) {
        String corporateName = null;
        if (account.getCorporateId() != null) {
            corporateName = corporateRepository.findById(account.getCorporateId())
                    .map(Corporate::getLegalName)
                    .orElse(null);
        }
        
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", account.getId());
        response.put("accountNumber", account.getAccountNumber());
        response.put("iban", account.getIban());
        response.put("accountName", account.getAccountName());
        response.put("accountType", account.getAccountType());
        
        // Bank info
        response.put("bankName", account.getBankName());
        response.put("bankCode", account.getBankCode());
        response.put("bankCountry", account.getBankCountry());
        response.put("branchName", account.getBranchName());
        
        // Entity info
        response.put("corporateId", account.getCorporateId());
        response.put("corporateName", corporateName);
        response.put("legalEntityId", account.getLegalEntityId());
        response.put("entityId", account.getLegalEntityId()); // Alias for frontend compatibility
        response.put("entityName", account.getEntityName() != null ? account.getEntityName() : corporateName);
        response.put("entityCode", account.getEntityCode());
        
        // Balances
        response.put("currency", account.getCurrencyCode());
        response.put("currentBalance", account.getCurrentBalance());
        response.put("availableBalance", account.getAvailableBalance());
        response.put("ledgerBalance", account.getLedgerBalance());
        
        // Interest
        response.put("interestRate", account.getInterestRate());
        response.put("interestType", account.getInterestType());
        // Simulator Phase 2 (additive — pre-existing schema columns)
        response.put("effectiveInterestRate", account.getEffectiveInterestRate());
        response.put("overdraftLimit", account.getOverdraftLimit());
        response.put("overdraftUtilized", account.getOverdraftUtilized());
        
        // Treasury
        response.put("poolingEnabled", account.getPoolingEnabled());
        response.put("poolReference", account.getPoolReference());
        response.put("sweepEnabled", account.getSweepEnabled());
        response.put("sweepRole", account.getSweepRole());
        
        // Bank Relationship (Home vs External) — derived from the configured
        // home-bank BIC (vam.home-bank.bic), the SAME source the pool /
        // activation guard uses (NotionalPoolService.assertPoolEligible →
        // VirtualAccount.isHomeBankHeld), so UI eligibility and live
        // activation always agree and track the boot-time VAM_HOME_BANK_BIC.
        // The stored physical_accounts.bank_relationship column is NOT trusted
        // (seeded data defaults it to INTERNAL for every row).
        boolean isHome = homeBank.matches(account.getBankCode());
        response.put("bankRelationship", isHome ? "INTERNAL" : "EXTERNAL");
        response.put("dataSource", account.getDataSource());
        response.put("isHomeBank", isHome);
        response.put("isExternalBank", !isHome);
        response.put("hasRealTimeData", account.hasRealTimeData());
        
        // External bank specific
        response.put("apiProvider", account.getApiProvider());
        response.put("consentExpiresAt", account.getConsentExpiresAt());
        
        // Capabilities
        response.put("canViewBalance", account.getCanViewBalance());
        response.put("canViewTransactions", account.getCanViewTransactions());
        response.put("canInitiatePayments", account.getCanInitiatePayments());
        response.put("canReceiveTransfers", account.getCanReceiveTransfers());
        response.put("canHostVirtualAccounts", account.getCanHostVirtualAccounts());
        response.put("poolingEligible", account.getPoolingEligible());
        response.put("sweepEligible", account.getSweepEligible());
        
        // Virtual Account linkage
        response.put("virtualAccountCount", account.getVirtualAccountCount());
        response.put("shadowVaId", account.getShadowVaId());
        
        // Status
        response.put("status", account.getStatus());
        response.put("syncStatus", account.getSyncStatus());
        response.put("lastSyncAt", account.getLastSyncAt());
        response.put("lastTransactionAt", account.getLastTransactionAt());
        response.put("balanceAsOf", account.getBalanceAsOf());
        response.put("syncFrequencyMinutes", account.getSyncFrequencyMinutes());
        
        response.put("createdAt", account.getCreatedAt());
        
        return response;
    }

    private Map<String, Object> toAccountDetailResponse(PhysicalAccount account) {
        Map<String, Object> response = toAccountResponse(account);
        
        response.put("openedDate", account.getOpenedDate());
        response.put("closedDate", account.getClosedDate());
        response.put("relationshipManager", account.getRelationshipManager());
        response.put("bancsCustomerId", account.getBancsCustomerId());
        response.put("bancsAccountId", account.getBancsAccountId());
        response.put("poolId", account.getPoolId());
        response.put("sweepRuleId", account.getSweepRuleId());
        response.put("syncErrorMessage", account.getSyncErrorMessage());
        response.put("updatedAt", account.getUpdatedAt());
        
        return response;
    }

    private String formatEnumName(String name) {
        return Arrays.stream(name.split("_"))
                .map(word -> word.charAt(0) + word.substring(1).toLowerCase())
                .collect(Collectors.joining(" "));
    }

    // ========================================================================
    // REQUEST DTOS
    // ========================================================================

    /**
     * Create Account Request - UPDATED with legalEntityId support.
     * 
     * When legalEntityId is provided:
     * - entityName and entityCode are inherited from the legal entity
     * - currencyCode defaults to the entity's functional currency
     * - Validates entity belongs to the specified corporate
     * - Validates entity can hold physical accounts
     */
    public record CreateAccountRequest(
            UUID corporateId,
            UUID legalEntityId,      // NEW: Links to legal_entities table
            String accountNumber,
            String iban,
            String accountName,
            String accountType,
            String bankName,
            String bankCode,
            String bankCountry,
            String branchName,
            String branchCode,
            String bankRelationship, // INTERNAL or EXTERNAL
            String dataSource,       // CORE_BANKING, OPEN_BANKING_UAE, etc.
            String entityName,       // Optional: overridden by legalEntityId
            String entityCode,       // Optional: overridden by legalEntityId
            String currencyCode,     // Optional: defaults to entity's functional currency
            BigDecimal initialBalance,
            BigDecimal interestRate,
            String interestType,
            String relationshipManager
    ) {}

    public record UpdateAccountRequest(
            UUID legalEntityId,      // NEW: Can update legal entity
            String accountName,
            String branchName,
            String branchCode,
            String entityName,
            String entityCode,
            BigDecimal interestRate,
            String relationshipManager
    ) {}

    public record StatusUpdateRequest(String status) {}

    public record PoolingConfigRequest(UUID poolId, String poolReference) {}

    public record SweepConfigRequest(UUID sweepRuleId, String role) {}
}