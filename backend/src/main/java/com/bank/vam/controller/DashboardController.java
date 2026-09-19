package com.bank.vam.controller;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.entity.Corporate;
import com.bank.vam.entity.Transaction;
import com.bank.vam.entity.payables.Payable;
import com.bank.vam.entity.treasury.NettingCycle;
import com.bank.vam.repository.CorporateRepository;
import com.bank.vam.repository.TransactionRepository;
import com.bank.vam.repository.payables.PayableRepository;
import com.bank.vam.repository.treasury.NettingCycleRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Dashboard API. The only endpoint is /pending-approvals, which backs the Payments block on the
 * Treasury 2030 dashboard. The rest of this controller (stats, balance trend, cash flow, top
 * accounts, recent transactions, treasury summary, receivables/payables, alerts, widgets, account
 * distribution) returned mock data and had no remaining consumer, so it was removed.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@Tag(name = "Dashboard", description = "Dashboard APIs")
public class DashboardController {

    private final CorporateRepository corporateRepository;
    private final TransactionRepository transactionRepository;
    private final NettingCycleRepository nettingCycleRepository;
    private final PayableRepository payableRepository;

    @GetMapping("/pending-approvals")
    @Operation(summary = "Get all pending approvals across modules")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getPendingApprovals(
            @RequestParam(required = false) UUID corporateId) {
        Map<String, Object> approvals = new HashMap<>();
        
        // Pending netting cycles
        List<Map<String, Object>> pendingNetting = new ArrayList<>();
        try {
            List<NettingCycle> cycles = nettingCycleRepository.findByStatus(NettingCycle.CycleStatus.PENDING_APPROVAL);
            for (NettingCycle cycle : cycles) {
                Map<String, Object> item = new HashMap<>();
                item.put("id", cycle.getId().toString());
                item.put("reference", cycle.getCycleReference());
                item.put("name", cycle.getCycleName());
                item.put("amount", cycle.getTotalNet());
                item.put("savingsAmount", cycle.getSavingsAmount());
                item.put("createdAt", cycle.getCreatedAt() != null ? cycle.getCreatedAt().toString() : null);
                pendingNetting.add(item);
            }
        } catch (Exception e) {
            log.warn("Error fetching pending netting cycles: {}", e.getMessage());
        }
        approvals.put("nettingCycles", pendingNetting);
        approvals.put("nettingCount", pendingNetting.size());
        
        // Pending KYC
        List<Map<String, Object>> pendingKyc = new ArrayList<>();
        try {
            List<Corporate> corps = corporateRepository.findByStatus(Corporate.CorporateStatus.PENDING);
            for (Corporate corp : corps) {
                Map<String, Object> item = new HashMap<>();
                item.put("id", corp.getId().toString());
                item.put("corporateId", corp.getCorporateId());
                item.put("name", corp.getLegalName());
                item.put("submittedAt", corp.getCreatedAt() != null ? corp.getCreatedAt().toString() : null);
                pendingKyc.add(item);
            }
        } catch (Exception e) {
            log.warn("Error fetching pending KYC: {}", e.getMessage());
        }
        approvals.put("kycApplications", pendingKyc);
        approvals.put("kycCount", pendingKyc.size());
        
        // Payables the user can act on now: awaiting approval, or approved/scheduled with a source VA
        // (payable via Pay Now). Earliest due first; payablesCount is the true total, list is capped.
        List<Map<String, Object>> pendingPayables = new ArrayList<>();
        int payablesCount = 0;
        try {
            List<Payable.PayableStatus> payableNow = List.of(Payable.PayableStatus.APPROVED,
                Payable.PayableStatus.SCHEDULED, Payable.PayableStatus.PARTIAL, Payable.PayableStatus.POBO_APPROVED);
            PageRequest top = PageRequest.of(0, 5, org.springframework.data.domain.Sort.by("dueDate").ascending());
            org.springframework.data.domain.Page<Payable> page = corporateId != null
                ? payableRepository.findDashboardActionableByCorporate(corporateId, Payable.PayableStatus.PENDING_APPROVAL, payableNow, top)
                : payableRepository.findDashboardActionable(Payable.PayableStatus.PENDING_APPROVAL, payableNow, top);
            payablesCount = (int) page.getTotalElements();
            for (Payable p : page.getContent()) {
                Map<String, Object> item = new HashMap<>();
                item.put("id", p.getId().toString());
                item.put("invoiceNumber", p.getInvoiceNumber() != null ? p.getInvoiceNumber() : p.getPayableNumber());
                item.put("vendorName", p.getVendorName());
                item.put("amount", p.getOutstandingAmount() != null ? p.getOutstandingAmount() : p.getNetAmount());
                item.put("currencyCode", p.getCurrencyCode());
                item.put("dueDate", p.getDueDate() != null ? p.getDueDate().toString() : null);
                item.put("status", p.getStatus().name());
                item.put("action", p.getStatus() == Payable.PayableStatus.PENDING_APPROVAL ? "APPROVE" : "PAY");
                pendingPayables.add(item);
            }
        } catch (Exception e) {
            log.warn("Error fetching actionable payables: {}", e.getMessage());
        }
        approvals.put("payables", pendingPayables);
        approvals.put("payablesCount", payablesCount);
        
        // Pending transactions
        List<Map<String, Object>> pendingTxns = new ArrayList<>();
        try {
            transactionRepository.findByStatus(Transaction.TransactionStatus.PENDING, PageRequest.of(0, 5))
                .getContent()
                .forEach(tx -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("id", tx.getId().toString());
                    item.put("reference", tx.getReferenceNumber());
                    item.put("amount", tx.getAmount());
                    item.put("description", tx.getDescription());
                    item.put("createdAt", tx.getCreatedAt() != null ? tx.getCreatedAt().toString() : null);
                    pendingTxns.add(item);
                });
        } catch (Exception e) {
            log.warn("Error fetching pending transactions: {}", e.getMessage());
        }
        approvals.put("transactions", pendingTxns);
        approvals.put("transactionsCount", pendingTxns.size());
        
        // Total pending count
        int totalPending = pendingNetting.size() + pendingKyc.size() + 
                          payablesCount + pendingTxns.size();
        approvals.put("totalPending", totalPending);
        
        return ResponseEntity.ok(ApiResponse.success(approvals));
    }
}
