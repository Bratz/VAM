package com.bank.vam.iso20022.service;

import com.bank.vam.entity.Transaction;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.iso20022.dto.CamtStatementDto.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ISO 20022 camt.053/054 XML Generator.
 *
 * Generates ISO 20022 compliant Bank-to-Customer Statement (camt.053.001.08)
 * and Bank-to-Customer Debit/Credit Notification (camt.054.001.08) messages.
 *
 * <h2>Message Types</h2>
 * <ul>
 *   <li><b>camt.053</b> - End-of-day Bank-to-Customer Statement</li>
 *   <li><b>camt.054</b> - Bank-to-Customer Debit/Credit Notification (real-time)</li>
 * </ul>
 *
 * <h2>Key Features</h2>
 * <ul>
 *   <li>Full ISO 20022 namespace compliance</li>
 *   <li>Balance types: OPBD, CLBD, PRCD, ITBD, CLAV, FWAV, INFO</li>
 *   <li>Entry status: BOOK, PDNG, INFO</li>
 *   <li>Transaction details with remittance information</li>
 *   <li>Aggregation account support with child VA identifiers</li>
 *   <li>Supplementary data for VA hierarchy information</li>
 *   <li>XSD validation support</li>
 * </ul>
 *
 * <h2>Aggregation Account Support</h2>
 * For aggregation/root accounts, the generator includes:
 * <ul>
 *   <li>Child VA identifiers in entry details (NtryDtls/TxDtls/RltdAcct)</li>
 *   <li>VA hierarchy path in supplementary data</li>
 *   <li>Aggregated totals across child accounts</li>
 * </ul>
 *
 * @see <a href="https://www.iso20022.org/catalogue-messages/iso-20022-messages-archive?search=camt.053">ISO 20022 camt.053</a>
 */
@Service
@Slf4j
public class Camt053XmlGenerator {

    // ISO 20022 namespaces
    private static final String CAMT053_NAMESPACE = "urn:iso:std:iso:20022:tech:xsd:camt.053.001.08";
    private static final String CAMT054_NAMESPACE = "urn:iso:std:iso:20022:tech:xsd:camt.054.001.08";

    // Date/Time formatters per ISO 20022 spec
    private static final DateTimeFormatter ISO_DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    // Bank identification (configurable in real implementation)
    private static final String DEFAULT_BIC = "BANKAEXX";
    private static final String DEFAULT_BANK_NAME = "Virtual Account Bank";

    // ========================================================================
    // camt.053 - BANK-TO-CUSTOMER STATEMENT (End of Day)
    // ========================================================================

    /**
     * Generate a complete camt.053.001.08 Bank-to-Customer Statement.
     *
     * @param request Statement generation request
     * @return Generated XML string
     */
    public String generateCamt053(Camt053Request request) {
        log.debug("Generating camt.053 for account {} from {} to {}",
                request.getAccount().getVaNumber(), request.getFromDate(), request.getToDate());

        long startTime = System.currentTimeMillis();
        StringBuilder xml = new StringBuilder(16384); // Pre-size for performance

        // XML Declaration and Document Root
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<Document xmlns=\"").append(CAMT053_NAMESPACE)
           .append("\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\n");
        xml.append("  <BkToCstmrStmt>\n");

        // Group Header (GrpHdr)
        appendGroupHeader(xml, request);

        // Statement (Stmt) - can have multiple statements in one message
        appendStatement(xml, request);

        // Close document
        xml.append("  </BkToCstmrStmt>\n");
        xml.append("</Document>");

        log.debug("Generated camt.053 in {}ms", System.currentTimeMillis() - startTime);
        return xml.toString();
    }

    /**
     * Generate camt.053 for an aggregation account with child VA details.
     *
     * @param request Aggregation statement request with child account mapping
     * @return Generated XML string
     */
    public String generateCamt053Aggregated(Camt053AggregatedRequest request) {
        log.debug("Generating aggregated camt.053 for root account {} with {} child accounts",
                request.getRootAccount().getVaNumber(), request.getChildAccounts().size());

        long startTime = System.currentTimeMillis();
        StringBuilder xml = new StringBuilder(32768);

        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<Document xmlns=\"").append(CAMT053_NAMESPACE)
           .append("\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\n");
        xml.append("  <BkToCstmrStmt>\n");

        // Group Header
        appendGroupHeaderAggregated(xml, request);

        // Aggregated Statement
        appendAggregatedStatement(xml, request);

        xml.append("  </BkToCstmrStmt>\n");
        xml.append("</Document>");

        log.debug("Generated aggregated camt.053 in {}ms", System.currentTimeMillis() - startTime);
        return xml.toString();
    }

    // ========================================================================
    // camt.054 - BANK-TO-CUSTOMER DEBIT/CREDIT NOTIFICATION (Real-time)
    // ========================================================================

    /**
     * Generate camt.054.001.08 Bank-to-Customer Debit/Credit Notification.
     * Used for real-time payment notifications (inward payments via VIBAN routing).
     *
     * @param request Notification request
     * @return Generated XML string
     */
    public String generateCamt054(Camt054Request request) {
        log.debug("Generating camt.054 notification for {} transactions",
                request.getTransactions().size());

        StringBuilder xml = new StringBuilder(8192);

        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<Document xmlns=\"").append(CAMT054_NAMESPACE)
           .append("\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\n");
        xml.append("  <BkToCstmrDbtCdtNtfctn>\n");

        // Group Header
        appendGroupHeaderNtfctn(xml, request);

        // Notification
        appendNotification(xml, request);

        xml.append("  </BkToCstmrDbtCdtNtfctn>\n");
        xml.append("</Document>");

        return xml.toString();
    }

    // ========================================================================
    // GROUP HEADER BUILDERS
    // ========================================================================

    private void appendGroupHeader(StringBuilder xml, Camt053Request request) {
        String messageId = request.getMessageId() != null ? request.getMessageId() :
                generateMessageId("STMT");

        xml.append("    <GrpHdr>\n");
        xml.append("      <MsgId>").append(escape(messageId)).append("</MsgId>\n");
        xml.append("      <CreDtTm>").append(LocalDateTime.now().format(ISO_DATETIME)).append("</CreDtTm>\n");

        // Message Recipient (optional)
        if (request.getRecipientBic() != null) {
            xml.append("      <MsgRcpt>\n");
            xml.append("        <Id>\n");
            xml.append("          <OrgId>\n");
            xml.append("            <AnyBIC>").append(escape(request.getRecipientBic())).append("</AnyBIC>\n");
            xml.append("          </OrgId>\n");
            xml.append("        </Id>\n");
            xml.append("      </MsgRcpt>\n");
        }

        // Message Pagination (for large statements)
        if (request.getPageNumber() != null) {
            xml.append("      <MsgPgntn>\n");
            xml.append("        <PgNb>").append(request.getPageNumber()).append("</PgNb>\n");
            xml.append("        <LastPgInd>").append(request.isLastPage()).append("</LastPgInd>\n");
            xml.append("      </MsgPgntn>\n");
        }

        // Additional Information (optional)
        if (request.getAdditionalInfo() != null) {
            xml.append("      <AddtlInf>").append(escape(request.getAdditionalInfo())).append("</AddtlInf>\n");
        }

        xml.append("    </GrpHdr>\n");
    }

    private void appendGroupHeaderAggregated(StringBuilder xml, Camt053AggregatedRequest request) {
        String messageId = request.getMessageId() != null ? request.getMessageId() :
                generateMessageId("AGGR");

        xml.append("    <GrpHdr>\n");
        xml.append("      <MsgId>").append(escape(messageId)).append("</MsgId>\n");
        xml.append("      <CreDtTm>").append(LocalDateTime.now().format(ISO_DATETIME)).append("</CreDtTm>\n");
        xml.append("      <AddtlInf>Aggregated Statement for ")
           .append(request.getChildAccounts().size())
           .append(" child accounts</AddtlInf>\n");
        xml.append("    </GrpHdr>\n");
    }

    private void appendGroupHeaderNtfctn(StringBuilder xml, Camt054Request request) {
        String messageId = request.getMessageId() != null ? request.getMessageId() :
                generateMessageId("NTFN");

        xml.append("    <GrpHdr>\n");
        xml.append("      <MsgId>").append(escape(messageId)).append("</MsgId>\n");
        xml.append("      <CreDtTm>").append(LocalDateTime.now().format(ISO_DATETIME)).append("</CreDtTm>\n");
        xml.append("    </GrpHdr>\n");
    }

    // ========================================================================
    // STATEMENT BUILDER (camt.053)
    // ========================================================================

    private void appendStatement(StringBuilder xml, Camt053Request request) {
        VirtualAccount va = request.getAccount();
        String statementId = request.getStatementId() != null ? request.getStatementId() :
                generateStatementId(va.getVaNumber());

        xml.append("    <Stmt>\n");
        xml.append("      <Id>").append(escape(statementId)).append("</Id>\n");
        xml.append("      <ElctrncSeqNb>").append(request.getSequenceNumber()).append("</ElctrncSeqNb>\n");

        // Legal Sequence Number (for compliance)
        if (request.getLegalSequenceNumber() != null) {
            xml.append("      <LglSeqNb>").append(request.getLegalSequenceNumber()).append("</LglSeqNb>\n");
        }

        xml.append("      <CreDtTm>").append(LocalDateTime.now().format(ISO_DATETIME)).append("</CreDtTm>\n");

        // Statement Period
        xml.append("      <FrToDt>\n");
        xml.append("        <FrDtTm>").append(request.getFromDate().atStartOfDay().format(ISO_DATETIME)).append("</FrDtTm>\n");
        xml.append("        <ToDtTm>").append(request.getToDate().atTime(23, 59, 59).format(ISO_DATETIME)).append("</ToDtTm>\n");
        xml.append("      </FrToDt>\n");

        // Account Identification
        appendAccount(xml, va);

        // Balances (Opening, Closing, etc.)
        appendBalances(xml, request);

        // Transaction Summary
        appendTransactionSummary(xml, request);

        // Entries (individual transactions)
        appendEntries(xml, request.getTransactions(), va);

        // Supplementary Data (VA hierarchy info)
        if (va.hasHierarchy()) {
            appendSupplementaryData(xml, va, null);
        }

        xml.append("    </Stmt>\n");
    }

    private void appendAggregatedStatement(StringBuilder xml, Camt053AggregatedRequest request) {
        VirtualAccount rootVa = request.getRootAccount();
        String statementId = request.getStatementId() != null ? request.getStatementId() :
                generateStatementId(rootVa.getVaNumber() + "-AGG");

        xml.append("    <Stmt>\n");
        xml.append("      <Id>").append(escape(statementId)).append("</Id>\n");
        xml.append("      <ElctrncSeqNb>").append(request.getSequenceNumber()).append("</ElctrncSeqNb>\n");
        xml.append("      <CreDtTm>").append(LocalDateTime.now().format(ISO_DATETIME)).append("</CreDtTm>\n");

        // Statement Period
        xml.append("      <FrToDt>\n");
        xml.append("        <FrDtTm>").append(request.getFromDate().atStartOfDay().format(ISO_DATETIME)).append("</FrDtTm>\n");
        xml.append("        <ToDtTm>").append(request.getToDate().atTime(23, 59, 59).format(ISO_DATETIME)).append("</ToDtTm>\n");
        xml.append("      </FrToDt>\n");

        // Root Account Identification (marked as aggregation account)
        appendAggregationAccount(xml, rootVa, request.getChildAccounts().size());

        // Aggregated Balances
        appendAggregatedBalances(xml, request);

        // Aggregated Transaction Summary
        appendAggregatedTransactionSummary(xml, request);

        // Entries with child account references
        appendAggregatedEntries(xml, request);

        // Supplementary Data with hierarchy info
        appendSupplementaryData(xml, rootVa, request.getChildAccounts());

        xml.append("    </Stmt>\n");
    }

    // ========================================================================
    // NOTIFICATION BUILDER (camt.054)
    // ========================================================================

    private void appendNotification(StringBuilder xml, Camt054Request request) {
        VirtualAccount va = request.getAccount();
        String notificationId = request.getNotificationId() != null ? request.getNotificationId() :
                generateNotificationId(va.getVaNumber());

        xml.append("    <Ntfctn>\n");
        xml.append("      <Id>").append(escape(notificationId)).append("</Id>\n");
        xml.append("      <ElctrncSeqNb>").append(request.getSequenceNumber()).append("</ElctrncSeqNb>\n");
        xml.append("      <CreDtTm>").append(LocalDateTime.now().format(ISO_DATETIME)).append("</CreDtTm>\n");

        // Account Identification
        appendAccount(xml, va);

        // Entries (notifications)
        appendNotificationEntries(xml, request.getTransactions(), va);

        xml.append("    </Ntfctn>\n");
    }

    // ========================================================================
    // ACCOUNT BUILDER
    // ========================================================================

    private void appendAccount(StringBuilder xml, VirtualAccount va) {
        xml.append("      <Acct>\n");
        xml.append("        <Id>\n");

        // Use VIBAN if available, otherwise use IBAN format
        String accountId = va.getViban() != null ? va.getViban() : va.getVaNumber();
        if (isValidIban(accountId)) {
            xml.append("          <IBAN>").append(escape(accountId)).append("</IBAN>\n");
        } else {
            xml.append("          <Othr>\n");
            xml.append("            <Id>").append(escape(va.getVaNumber())).append("</Id>\n");
            xml.append("            <SchmeNm>\n");
            xml.append("              <Prtry>VA_NUMBER</Prtry>\n");
            xml.append("            </SchmeNm>\n");
            xml.append("          </Othr>\n");
        }

        xml.append("        </Id>\n");
        xml.append("        <Tp>\n");
        xml.append("          <Prtry>VIRTUAL_ACCOUNT</Prtry>\n");
        xml.append("        </Tp>\n");
        xml.append("        <Ccy>").append(va.getCurrencyCode()).append("</Ccy>\n");
        xml.append("        <Nm>").append(escape(va.getVaName())).append("</Nm>\n");

        // Account Servicer (Bank)
        xml.append("        <Svcr>\n");
        xml.append("          <FinInstnId>\n");
        xml.append("            <BICFI>").append(DEFAULT_BIC).append("</BICFI>\n");
        xml.append("            <Nm>").append(escape(DEFAULT_BANK_NAME)).append("</Nm>\n");
        xml.append("          </FinInstnId>\n");
        xml.append("        </Svcr>\n");

        xml.append("      </Acct>\n");
    }

    private void appendAggregationAccount(StringBuilder xml, VirtualAccount rootVa, int childCount) {
        xml.append("      <Acct>\n");
        xml.append("        <Id>\n");
        xml.append("          <Othr>\n");
        xml.append("            <Id>").append(escape(rootVa.getVaNumber())).append("</Id>\n");
        xml.append("            <SchmeNm>\n");
        xml.append("              <Prtry>AGGREGATION_ACCOUNT</Prtry>\n");
        xml.append("            </SchmeNm>\n");
        xml.append("            <Issr>VAM_SYSTEM</Issr>\n");
        xml.append("          </Othr>\n");
        xml.append("        </Id>\n");
        xml.append("        <Tp>\n");
        xml.append("          <Prtry>ROOT_AGGREGATION</Prtry>\n");
        xml.append("        </Tp>\n");
        xml.append("        <Ccy>").append(rootVa.getCurrencyCode()).append("</Ccy>\n");
        xml.append("        <Nm>").append(escape(rootVa.getVaName())).append(" (Aggregated: ")
           .append(childCount).append(" accounts)</Nm>\n");
        xml.append("        <Svcr>\n");
        xml.append("          <FinInstnId>\n");
        xml.append("            <BICFI>").append(DEFAULT_BIC).append("</BICFI>\n");
        xml.append("          </FinInstnId>\n");
        xml.append("        </Svcr>\n");
        xml.append("      </Acct>\n");
    }

    // ========================================================================
    // BALANCE BUILDERS
    // ========================================================================

    private void appendBalances(StringBuilder xml, Camt053Request request) {
        VirtualAccount va = request.getAccount();

        // Opening Balance (OPBD - Opening Booked)
        appendBalance(xml, "OPBD", request.getOpeningBalance(),
                va.getCurrencyCode(), request.getFromDate());

        // Closing Balance (CLBD - Closing Booked)
        appendBalance(xml, "CLBD", request.getClosingBalance(),
                va.getCurrencyCode(), request.getToDate());

        // Closing Available (CLAV - optional)
        if (request.getClosingAvailable() != null) {
            appendBalance(xml, "CLAV", request.getClosingAvailable(),
                    va.getCurrencyCode(), request.getToDate());
        }

        // Forward Available (FWAV - optional, for projected balance)
        if (request.getForwardAvailable() != null) {
            appendBalance(xml, "FWAV", request.getForwardAvailable(),
                    va.getCurrencyCode(), request.getToDate().plusDays(1));
        }
    }

    private void appendAggregatedBalances(StringBuilder xml, Camt053AggregatedRequest request) {
        VirtualAccount rootVa = request.getRootAccount();

        // Opening Aggregated Balance
        appendBalance(xml, "OPBD", request.getAggregatedOpeningBalance(),
                rootVa.getCurrencyCode(), request.getFromDate());

        // Closing Aggregated Balance
        appendBalance(xml, "CLBD", request.getAggregatedClosingBalance(),
                rootVa.getCurrencyCode(), request.getToDate());

        // Information Balance (INFO) - total across children
        xml.append("      <Bal>\n");
        xml.append("        <Tp>\n");
        xml.append("          <CdOrPrtry>\n");
        xml.append("            <Prtry>AGGR_TOTAL</Prtry>\n");
        xml.append("          </CdOrPrtry>\n");
        xml.append("        </Tp>\n");
        xml.append("        <Amt Ccy=\"").append(rootVa.getCurrencyCode()).append("\">")
           .append(formatAmount(rootVa.getAggregatedBalance())).append("</Amt>\n");
        xml.append("        <CdtDbtInd>")
           .append(rootVa.getAggregatedBalance().compareTo(BigDecimal.ZERO) >= 0 ? "CRDT" : "DBIT")
           .append("</CdtDbtInd>\n");
        xml.append("        <Dt><Dt>").append(request.getToDate().format(ISO_DATE)).append("</Dt></Dt>\n");
        xml.append("      </Bal>\n");
    }

    private void appendBalance(StringBuilder xml, String balanceType, BigDecimal amount,
                                String currency, LocalDate date) {
        if (amount == null) {
            amount = BigDecimal.ZERO;
        }

        xml.append("      <Bal>\n");
        xml.append("        <Tp>\n");
        xml.append("          <CdOrPrtry>\n");
        xml.append("            <Cd>").append(balanceType).append("</Cd>\n");
        xml.append("          </CdOrPrtry>\n");
        xml.append("        </Tp>\n");
        xml.append("        <Amt Ccy=\"").append(currency).append("\">")
           .append(formatAmount(amount.abs())).append("</Amt>\n");
        xml.append("        <CdtDbtInd>")
           .append(amount.compareTo(BigDecimal.ZERO) >= 0 ? "CRDT" : "DBIT")
           .append("</CdtDbtInd>\n");
        xml.append("        <Dt>\n");
        xml.append("          <Dt>").append(date.format(ISO_DATE)).append("</Dt>\n");
        xml.append("        </Dt>\n");
        xml.append("      </Bal>\n");
    }

    // ========================================================================
    // TRANSACTION SUMMARY BUILDER
    // ========================================================================

    private void appendTransactionSummary(StringBuilder xml, Camt053Request request) {
        List<Transaction> transactions = request.getTransactions();

        int creditCount = 0;
        int debitCount = 0;
        BigDecimal creditSum = BigDecimal.ZERO;
        BigDecimal debitSum = BigDecimal.ZERO;

        for (Transaction txn : transactions) {
            if (txn.isCredit()) {
                creditCount++;
                creditSum = creditSum.add(txn.getAmount());
            } else {
                debitCount++;
                debitSum = debitSum.add(txn.getAmount());
            }
        }

        xml.append("      <TxsSummry>\n");

        // Total Credit Entries
        xml.append("        <TtlCdtNtries>\n");
        xml.append("          <NbOfNtries>").append(creditCount).append("</NbOfNtries>\n");
        xml.append("          <Sum>").append(formatAmount(creditSum)).append("</Sum>\n");
        xml.append("        </TtlCdtNtries>\n");

        // Total Debit Entries
        xml.append("        <TtlDbtNtries>\n");
        xml.append("          <NbOfNtries>").append(debitCount).append("</NbOfNtries>\n");
        xml.append("          <Sum>").append(formatAmount(debitSum)).append("</Sum>\n");
        xml.append("        </TtlDbtNtries>\n");

        // Total Number of Entries
        xml.append("        <TtlNtries>\n");
        xml.append("          <NbOfNtries>").append(transactions.size()).append("</NbOfNtries>\n");
        xml.append("          <Sum>").append(formatAmount(creditSum.add(debitSum))).append("</Sum>\n");
        xml.append("          <TtlNetNtry>\n");
        xml.append("            <Amt>").append(formatAmount(creditSum.subtract(debitSum).abs())).append("</Amt>\n");
        xml.append("            <CdtDbtInd>")
           .append(creditSum.compareTo(debitSum) >= 0 ? "CRDT" : "DBIT")
           .append("</CdtDbtInd>\n");
        xml.append("          </TtlNetNtry>\n");
        xml.append("        </TtlNtries>\n");

        xml.append("      </TxsSummry>\n");
    }

    private void appendAggregatedTransactionSummary(StringBuilder xml, Camt053AggregatedRequest request) {
        int totalCreditCount = 0;
        int totalDebitCount = 0;
        BigDecimal totalCreditSum = BigDecimal.ZERO;
        BigDecimal totalDebitSum = BigDecimal.ZERO;

        for (Map.Entry<VirtualAccount, List<Transaction>> entry : request.getTransactionsByAccount().entrySet()) {
            for (Transaction txn : entry.getValue()) {
                if (txn.isCredit()) {
                    totalCreditCount++;
                    totalCreditSum = totalCreditSum.add(txn.getAmount());
                } else {
                    totalDebitCount++;
                    totalDebitSum = totalDebitSum.add(txn.getAmount());
                }
            }
        }

        xml.append("      <TxsSummry>\n");
        xml.append("        <TtlCdtNtries>\n");
        xml.append("          <NbOfNtries>").append(totalCreditCount).append("</NbOfNtries>\n");
        xml.append("          <Sum>").append(formatAmount(totalCreditSum)).append("</Sum>\n");
        xml.append("        </TtlCdtNtries>\n");
        xml.append("        <TtlDbtNtries>\n");
        xml.append("          <NbOfNtries>").append(totalDebitCount).append("</NbOfNtries>\n");
        xml.append("          <Sum>").append(formatAmount(totalDebitSum)).append("</Sum>\n");
        xml.append("        </TtlDbtNtries>\n");
        xml.append("      </TxsSummry>\n");
    }

    // ========================================================================
    // ENTRY BUILDERS (Ntry)
    // ========================================================================

    private void appendEntries(StringBuilder xml, List<Transaction> transactions, VirtualAccount va) {
        for (Transaction txn : transactions) {
            appendEntry(xml, txn, va, null);
        }
    }

    private void appendAggregatedEntries(StringBuilder xml, Camt053AggregatedRequest request) {
        for (Map.Entry<VirtualAccount, List<Transaction>> entry : request.getTransactionsByAccount().entrySet()) {
            VirtualAccount childVa = entry.getKey();
            for (Transaction txn : entry.getValue()) {
                appendEntry(xml, txn, childVa, childVa);
            }
        }
    }

    private void appendNotificationEntries(StringBuilder xml, List<Transaction> transactions, VirtualAccount va) {
        for (Transaction txn : transactions) {
            appendNotificationEntry(xml, txn, va);
        }
    }

    /**
     * Append a single entry (Ntry) element for camt.053.
     */
    private void appendEntry(StringBuilder xml, Transaction txn, VirtualAccount va,
                              VirtualAccount childVa) {
        xml.append("      <Ntry>\n");

        // Entry Reference
        if (txn.getReferenceNumber() != null) {
            xml.append("        <NtryRef>").append(escape(txn.getReferenceNumber())).append("</NtryRef>\n");
        }

        // Amount
        xml.append("        <Amt Ccy=\"").append(txn.getCurrencyCode()).append("\">")
           .append(formatAmount(txn.getAmount())).append("</Amt>\n");

        // Credit/Debit Indicator
        xml.append("        <CdtDbtInd>").append(txn.isCredit() ? "CRDT" : "DBIT").append("</CdtDbtInd>\n");

        // Reversal Indicator
        if (txn.getMovementType() == Transaction.MovementType.REVERSAL) {
            xml.append("        <RvslInd>true</RvslInd>\n");
        }

        // Status (BOOK = Booked, PDNG = Pending, INFO = Information)
        xml.append("        <Sts>\n");
        xml.append("          <Cd>").append(mapEntryStatus(txn.getStatus())).append("</Cd>\n");
        xml.append("        </Sts>\n");

        // Booking Date
        if (txn.getTransactionDate() != null) {
            xml.append("        <BookgDt>\n");
            xml.append("          <Dt>").append(txn.getTransactionDate().toLocalDate().format(ISO_DATE)).append("</Dt>\n");
            xml.append("        </BookgDt>\n");
        }

        // Value Date
        if (txn.getValueDate() != null) {
            xml.append("        <ValDt>\n");
            xml.append("          <Dt>").append(txn.getValueDate().format(ISO_DATE)).append("</Dt>\n");
            xml.append("        </ValDt>\n");
        }

        // Account Servicer Reference (bank's reference)
        xml.append("        <AcctSvcrRef>").append(escape(txn.getReferenceNumber())).append("</AcctSvcrRef>\n");

        // Bank Transaction Code
        appendBankTransactionCode(xml, txn);

        // Entry Details
        appendEntryDetails(xml, txn, va, childVa);

        // Additional Entry Information
        if (txn.getDescription() != null) {
            xml.append("        <AddtlNtryInf>").append(escape(txn.getDescription())).append("</AddtlNtryInf>\n");
        }

        xml.append("      </Ntry>\n");
    }

    /**
     * Append a notification entry for camt.054.
     */
    private void appendNotificationEntry(StringBuilder xml, Transaction txn, VirtualAccount va) {
        xml.append("      <Ntry>\n");

        if (txn.getReferenceNumber() != null) {
            xml.append("        <NtryRef>").append(escape(txn.getReferenceNumber())).append("</NtryRef>\n");
        }

        xml.append("        <Amt Ccy=\"").append(txn.getCurrencyCode()).append("\">")
           .append(formatAmount(txn.getAmount())).append("</Amt>\n");
        xml.append("        <CdtDbtInd>").append(txn.isCredit() ? "CRDT" : "DBIT").append("</CdtDbtInd>\n");

        xml.append("        <Sts>\n");
        xml.append("          <Cd>BOOK</Cd>\n");
        xml.append("        </Sts>\n");

        if (txn.getTransactionDate() != null) {
            xml.append("        <BookgDt>\n");
            xml.append("          <DtTm>").append(txn.getTransactionDate().format(ISO_DATETIME)).append("</DtTm>\n");
            xml.append("        </BookgDt>\n");
        }

        if (txn.getValueDate() != null) {
            xml.append("        <ValDt>\n");
            xml.append("          <Dt>").append(txn.getValueDate().format(ISO_DATE)).append("</Dt>\n");
            xml.append("        </ValDt>\n");
        }

        xml.append("        <AcctSvcrRef>").append(escape(txn.getReferenceNumber())).append("</AcctSvcrRef>\n");

        appendBankTransactionCode(xml, txn);
        appendEntryDetails(xml, txn, va, null);

        xml.append("      </Ntry>\n");
    }

    // ========================================================================
    // ENTRY DETAILS BUILDER (NtryDtls)
    // ========================================================================

    private void appendEntryDetails(StringBuilder xml, Transaction txn, VirtualAccount va,
                                     VirtualAccount childVa) {
        xml.append("        <NtryDtls>\n");
        xml.append("          <TxDtls>\n");

        // References
        xml.append("            <Refs>\n");
        xml.append("              <MsgId>").append(escape(txn.getReferenceNumber())).append("</MsgId>\n");

        if (txn.getExternalReference() != null) {
            xml.append("              <AcctSvcrRef>").append(escape(txn.getExternalReference())).append("</AcctSvcrRef>\n");
        }

        if (txn.getCorrelationId() != null) {
            xml.append("              <EndToEndId>").append(escape(txn.getCorrelationId())).append("</EndToEndId>\n");
        }

        // UETR (Unique End-to-End Transaction Reference) for SWIFT gpi
        if (txn.getExternalReference() != null && txn.getExternalReference().length() == 36) {
            xml.append("              <UETR>").append(escape(txn.getExternalReference())).append("</UETR>\n");
        }

        xml.append("            </Refs>\n");

        // Amount Details
        xml.append("            <AmtDtls>\n");
        xml.append("              <TxAmt>\n");
        xml.append("                <Amt Ccy=\"").append(txn.getCurrencyCode()).append("\">")
           .append(formatAmount(txn.getAmount())).append("</Amt>\n");
        xml.append("              </TxAmt>\n");
        xml.append("            </AmtDtls>\n");

        // Related Parties
        appendRelatedParties(xml, txn);

        // Related Account (for aggregation - shows child VA)
        if (childVa != null) {
            xml.append("            <RltdAcct>\n");
            xml.append("              <Id>\n");
            if (childVa.getViban() != null) {
                xml.append("                <IBAN>").append(escape(childVa.getViban())).append("</IBAN>\n");
            } else {
                xml.append("                <Othr>\n");
                xml.append("                  <Id>").append(escape(childVa.getVaNumber())).append("</Id>\n");
                xml.append("                  <SchmeNm><Prtry>CHILD_VA</Prtry></SchmeNm>\n");
                xml.append("                </Othr>\n");
            }
            xml.append("              </Id>\n");
            xml.append("              <Nm>").append(escape(childVa.getVaName())).append("</Nm>\n");
            xml.append("            </RltdAcct>\n");
        }

        // Remittance Information
        appendRemittanceInfo(xml, txn);

        // Additional Transaction Information
        if (txn.getProcessingNotes() != null) {
            xml.append("            <AddtlTxInf>").append(escape(txn.getProcessingNotes())).append("</AddtlTxInf>\n");
        }

        xml.append("          </TxDtls>\n");
        xml.append("        </NtryDtls>\n");
    }

    private void appendRelatedParties(StringBuilder xml, Transaction txn) {
        boolean hasParties = txn.getRemitterName() != null || txn.getBeneficiaryName() != null;

        if (!hasParties) return;

        xml.append("            <RltdPties>\n");

        // Debtor (Remitter/Sender)
        if (txn.getRemitterName() != null) {
            xml.append("              <Dbtr>\n");
            xml.append("                <Pty>\n");
            xml.append("                  <Nm>").append(escape(txn.getRemitterName())).append("</Nm>\n");
            xml.append("                </Pty>\n");
            xml.append("              </Dbtr>\n");

            if (txn.getRemitterAccount() != null) {
                xml.append("              <DbtrAcct>\n");
                xml.append("                <Id>\n");
                if (isValidIban(txn.getRemitterAccount())) {
                    xml.append("                  <IBAN>").append(escape(txn.getRemitterAccount())).append("</IBAN>\n");
                } else {
                    xml.append("                  <Othr>\n");
                    xml.append("                    <Id>").append(escape(txn.getRemitterAccount())).append("</Id>\n");
                    xml.append("                  </Othr>\n");
                }
                xml.append("                </Id>\n");
                xml.append("              </DbtrAcct>\n");
            }
        }

        // Creditor (Beneficiary/Receiver)
        if (txn.getBeneficiaryName() != null) {
            xml.append("              <Cdtr>\n");
            xml.append("                <Pty>\n");
            xml.append("                  <Nm>").append(escape(txn.getBeneficiaryName())).append("</Nm>\n");
            xml.append("                </Pty>\n");
            xml.append("              </Cdtr>\n");

            if (txn.getBeneficiaryAccount() != null) {
                xml.append("              <CdtrAcct>\n");
                xml.append("                <Id>\n");
                if (isValidIban(txn.getBeneficiaryAccount())) {
                    xml.append("                  <IBAN>").append(escape(txn.getBeneficiaryAccount())).append("</IBAN>\n");
                } else {
                    xml.append("                  <Othr>\n");
                    xml.append("                    <Id>").append(escape(txn.getBeneficiaryAccount())).append("</Id>\n");
                    xml.append("                  </Othr>\n");
                }
                xml.append("                </Id>\n");
                xml.append("              </CdtrAcct>\n");
            }
        }

        xml.append("            </RltdPties>\n");
    }

    private void appendRemittanceInfo(StringBuilder xml, Transaction txn) {
        if (txn.getDescription() == null && txn.getReconciledReferenceId() == null) {
            return;
        }

        xml.append("            <RmtInf>\n");

        // Unstructured remittance info
        if (txn.getDescription() != null) {
            xml.append("              <Ustrd>").append(escape(txn.getDescription())).append("</Ustrd>\n");
        }

        // Structured remittance info (if auto-reconciled)
        if (txn.getReconciledReferenceId() != null) {
            xml.append("              <Strd>\n");
            xml.append("                <CdtrRefInf>\n");
            xml.append("                  <Tp>\n");
            xml.append("                    <CdOrPrtry>\n");
            xml.append("                      <Prtry>").append(escape(txn.getReconciledReferenceType())).append("</Prtry>\n");
            xml.append("                    </CdOrPrtry>\n");
            xml.append("                  </Tp>\n");
            xml.append("                  <Ref>").append(escape(txn.getReconciledReferenceId())).append("</Ref>\n");
            xml.append("                </CdtrRefInf>\n");
            xml.append("              </Strd>\n");
        }

        xml.append("            </RmtInf>\n");
    }

    // ========================================================================
    // BANK TRANSACTION CODE BUILDER
    // ========================================================================

    private void appendBankTransactionCode(StringBuilder xml, Transaction txn) {
        xml.append("        <BkTxCd>\n");
        xml.append("          <Domn>\n");
        xml.append("            <Cd>PMNT</Cd>\n");  // Payment domain
        xml.append("            <Fmly>\n");
        xml.append("              <Cd>").append(getBankTxFamilyCode(txn)).append("</Cd>\n");
        xml.append("              <SubFmlyCd>").append(getBankTxSubFamilyCode(txn)).append("</SubFmlyCd>\n");
        xml.append("            </Fmly>\n");
        xml.append("          </Domn>\n");
        xml.append("          <Prtry>\n");
        xml.append("            <Cd>").append(txn.getMovementType().name()).append("</Cd>\n");
        xml.append("            <Issr>VAM_SYSTEM</Issr>\n");
        xml.append("          </Prtry>\n");
        xml.append("        </BkTxCd>\n");
    }

    private String getBankTxFamilyCode(Transaction txn) {
        return switch (txn.getMovementType()) {
            case CREDIT, ROBO_CREDIT, TOPUP -> "RCDT";  // Received Credit Transfer
            case DEBIT, POBO_DEBIT, WITHDRAWAL, PAYMENT, PURCHASE -> "ICDT";  // Issued Credit Transfer
            case TRANSFER_IN, SWEEP_IN, POOL_CREDIT -> "RCDT";
            case TRANSFER_OUT, SWEEP_OUT, POOL_DEBIT -> "ICDT";
            case REVERSAL -> "RRCT";  // Received Reversal
            case FEE, FEE_CREDIT, CHARGE_CREDIT -> "CHRG";  // Charges
            case INTEREST, INTEREST_ALLOCATE -> "MCOP";  // Miscellaneous Credit Operations
            default -> "MCOP";
        };
    }

    private String getBankTxSubFamilyCode(Transaction txn) {
        return switch (txn.getMovementType()) {
            case ROBO_CREDIT -> "ESCT";  // Incoming SEPA Credit Transfer
            case POBO_DEBIT -> "DMCT";  // Domestic Credit Transfer
            case SWEEP_IN, SWEEP_OUT -> "SWEP";  // Sweep
            case POOL_CREDIT, POOL_DEBIT -> "POOL";  // Pool
            case REVERSAL -> "ARET";  // Auto Reversal
            case FEE, FEE_CREDIT, CHARGE_CREDIT -> "FEES";  // Fees
            case INTEREST, INTEREST_ALLOCATE -> "INTR";  // Interest
            default -> "OTHR";  // Other
        };
    }

    // ========================================================================
    // SUPPLEMENTARY DATA (for VA Hierarchy)
    // ========================================================================

    private void appendSupplementaryData(StringBuilder xml, VirtualAccount va,
                                          List<VirtualAccount> childAccounts) {
        xml.append("      <SplmtryData>\n");
        xml.append("        <Envlp>\n");

        // VA Hierarchy Information
        xml.append("          <VAMHierarchy xmlns=\"urn:vam:hierarchy:1.0\">\n");

        // Root Account Info
        xml.append("            <RootAccount>\n");
        xml.append("              <VaNumber>").append(escape(va.getVaNumber())).append("</VaNumber>\n");
        if (va.getHierarchyPathVa() != null) {
            xml.append("              <HierarchyPath>").append(escape(va.getHierarchyPathVa())).append("</HierarchyPath>\n");
        }
        xml.append("              <AccountCategory>").append(va.getAccountCategory()).append("</AccountCategory>\n");
        xml.append("              <HierarchyLevel>").append(va.getHierarchyLevel()).append("</HierarchyLevel>\n");
        xml.append("            </RootAccount>\n");

        // Child Accounts (for aggregation)
        if (childAccounts != null && !childAccounts.isEmpty()) {
            xml.append("            <ChildAccounts count=\"").append(childAccounts.size()).append("\">\n");
            for (VirtualAccount child : childAccounts) {
                xml.append("              <ChildAccount>\n");
                xml.append("                <VaNumber>").append(escape(child.getVaNumber())).append("</VaNumber>\n");
                if (child.getViban() != null) {
                    xml.append("                <Viban>").append(escape(child.getViban())).append("</Viban>\n");
                }
                xml.append("                <VaName>").append(escape(child.getVaName())).append("</VaName>\n");
                xml.append("                <Currency>").append(child.getCurrencyCode()).append("</Currency>\n");
                xml.append("                <Balance>").append(formatAmount(child.getCurrentBalance())).append("</Balance>\n");
                xml.append("              </ChildAccount>\n");
            }
            xml.append("            </ChildAccounts>\n");
        }

        xml.append("          </VAMHierarchy>\n");
        xml.append("        </Envlp>\n");
        xml.append("      </SplmtryData>\n");
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    private String escape(String value) {
        if (value == null) return "";
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private String formatAmount(BigDecimal amount) {
        if (amount == null) return "0.00";
        return amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private String generateMessageId(String prefix) {
        return prefix + "-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"))
                + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private String generateStatementId(String vaNumber) {
        return "STMT-" + vaNumber + "-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    private String generateNotificationId(String vaNumber) {
        return "NTFN-" + vaNumber + "-" + System.currentTimeMillis();
    }

    private boolean isValidIban(String value) {
        if (value == null || value.length() < 15 || value.length() > 34) {
            return false;
        }
        return value.matches("^[A-Z]{2}[0-9]{2}[A-Z0-9]+$");
    }

    private String mapEntryStatus(Transaction.TransactionStatus status) {
        return switch (status) {
            case COMPLETED -> "BOOK";
            case PENDING, PROCESSING, ON_HOLD -> "PDNG";
            case FAILED, CANCELLED, REVERSED -> "INFO";
            default -> "BOOK";
        };
    }
}
