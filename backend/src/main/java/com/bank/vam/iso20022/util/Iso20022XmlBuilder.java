package com.bank.vam.iso20022.util;

import com.bank.vam.entity.Transaction;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.iso20022.dto.Iso20022PaymentDto.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * ISO 20022 XML Builder - Generates compliant XML messages without external libraries.
 *
 * Supported message types:
 * - pain.001.001.09 - Customer Credit Transfer Initiation
 * - pain.002.001.10 - Customer Payment Status Report
 * - camt.053.001.08 - Bank-to-Customer Statement
 */
@Component
@Slf4j
public class Iso20022XmlBuilder {

    private static final DateTimeFormatter ISO_DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    // ========================================================================
    // pain.001 - Customer Credit Transfer Initiation
    // ========================================================================

    public String buildPain001(OutwardPaymentRequest request, VirtualAccount sourceVa, Transaction txn) {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pain.001.001.09\">\n");
        xml.append("  <CstmrCdtTrfInitn>\n");

        // Group Header
        xml.append("    <GrpHdr>\n");
        xml.append("      <MsgId>").append(escape(txn.getReferenceNumber())).append("</MsgId>\n");
        xml.append("      <CreDtTm>").append(LocalDateTime.now().format(ISO_DATETIME)).append("</CreDtTm>\n");
        xml.append("      <NbOfTxs>1</NbOfTxs>\n");
        xml.append("      <CtrlSum>").append(request.getAmount()).append("</CtrlSum>\n");
        xml.append("      <InitgPty>\n");
        xml.append("        <Nm>").append(escape(sourceVa.getVaName())).append("</Nm>\n");
        xml.append("      </InitgPty>\n");
        xml.append("    </GrpHdr>\n");

        // Payment Information
        xml.append("    <PmtInf>\n");
        xml.append("      <PmtInfId>").append(escape(txn.getReferenceNumber())).append("</PmtInfId>\n");
        xml.append("      <PmtMtd>TRF</PmtMtd>\n");
        xml.append("      <NbOfTxs>1</NbOfTxs>\n");
        xml.append("      <CtrlSum>").append(request.getAmount()).append("</CtrlSum>\n");

        // Requested Execution Date
        LocalDate execDate = request.getRequestedExecutionDate() != null ?
                request.getRequestedExecutionDate() : LocalDate.now();
        xml.append("      <ReqdExctnDt>\n");
        xml.append("        <Dt>").append(execDate.format(ISO_DATE)).append("</Dt>\n");
        xml.append("      </ReqdExctnDt>\n");

        // Debtor
        xml.append("      <Dbtr>\n");
        xml.append("        <Nm>").append(escape(request.getDebtorName() != null ? request.getDebtorName() : sourceVa.getVaName())).append("</Nm>\n");
        xml.append("      </Dbtr>\n");

        // Debtor Account
        xml.append("      <DbtrAcct>\n");
        xml.append("        <Id>\n");
        xml.append("          <IBAN>").append(escape(request.getDebtorAccount() != null ? request.getDebtorAccount() : sourceVa.getViban())).append("</IBAN>\n");
        xml.append("        </Id>\n");
        xml.append("      </DbtrAcct>\n");

        // Debtor Agent
        xml.append("      <DbtrAgt>\n");
        xml.append("        <FinInstnId>\n");
        xml.append("          <BICFI>").append(escape(request.getDebtorBic() != null ? request.getDebtorBic() : "BANKAEXX")).append("</BICFI>\n");
        xml.append("        </FinInstnId>\n");
        xml.append("      </DbtrAgt>\n");

        // Credit Transfer Transaction Information
        xml.append("      <CdtTrfTxInf>\n");

        // Payment ID
        xml.append("        <PmtId>\n");
        xml.append("          <InstrId>").append(escape(request.getInstructionId() != null ? request.getInstructionId() : txn.getReferenceNumber())).append("</InstrId>\n");
        xml.append("          <EndToEndId>").append(escape(request.getEndToEndId() != null ? request.getEndToEndId() : txn.getCorrelationId())).append("</EndToEndId>\n");
        xml.append("        </PmtId>\n");

        // Amount
        String currency = request.getCurrency() != null ? request.getCurrency() : sourceVa.getCurrencyCode();
        xml.append("        <Amt>\n");
        xml.append("          <InstdAmt Ccy=\"").append(currency).append("\">").append(request.getAmount()).append("</InstdAmt>\n");
        xml.append("        </Amt>\n");

        // Creditor Agent
        if (request.getCreditorBic() != null) {
            xml.append("        <CdtrAgt>\n");
            xml.append("          <FinInstnId>\n");
            xml.append("            <BICFI>").append(escape(request.getCreditorBic())).append("</BICFI>\n");
            xml.append("          </FinInstnId>\n");
            xml.append("        </CdtrAgt>\n");
        }

        // Creditor
        xml.append("        <Cdtr>\n");
        xml.append("          <Nm>").append(escape(request.getCreditorName())).append("</Nm>\n");
        xml.append("        </Cdtr>\n");

        // Creditor Account
        xml.append("        <CdtrAcct>\n");
        xml.append("          <Id>\n");
        xml.append("            <IBAN>").append(escape(request.getCreditorAccount())).append("</IBAN>\n");
        xml.append("          </Id>\n");
        xml.append("        </CdtrAcct>\n");

        // Remittance Information
        if (request.getRemittanceInfo() != null || request.getStructuredRef() != null) {
            xml.append("        <RmtInf>\n");
            if (request.getRemittanceInfo() != null) {
                xml.append("          <Ustrd>").append(escape(request.getRemittanceInfo())).append("</Ustrd>\n");
            }
            if (request.getStructuredRef() != null) {
                xml.append("          <Strd>\n");
                xml.append("            <CdtrRefInf>\n");
                xml.append("              <Ref>").append(escape(request.getStructuredRef())).append("</Ref>\n");
                xml.append("            </CdtrRefInf>\n");
                xml.append("          </Strd>\n");
            }
            xml.append("        </RmtInf>\n");
        }

        xml.append("      </CdtTrfTxInf>\n");
        xml.append("    </PmtInf>\n");
        xml.append("  </CstmrCdtTrfInitn>\n");
        xml.append("</Document>");

        return xml.toString();
    }

    /**
     * Build bulk pain.001 with multiple credit transfers.
     */
    public String buildBulkPain001(BulkPaymentRequest request, VirtualAccount sourceVa, List<Transaction> transactions) {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pain.001.001.09\">\n");
        xml.append("  <CstmrCdtTrfInitn>\n");

        // Calculate totals
        BigDecimal totalAmount = transactions.stream()
                .map(Transaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        String messageId = request.getMessageId() != null ? request.getMessageId() :
                "BULK" + System.currentTimeMillis();

        // Group Header
        xml.append("    <GrpHdr>\n");
        xml.append("      <MsgId>").append(escape(messageId)).append("</MsgId>\n");
        xml.append("      <CreDtTm>").append(LocalDateTime.now().format(ISO_DATETIME)).append("</CreDtTm>\n");
        xml.append("      <NbOfTxs>").append(transactions.size()).append("</NbOfTxs>\n");
        xml.append("      <CtrlSum>").append(totalAmount).append("</CtrlSum>\n");
        xml.append("      <InitgPty>\n");
        xml.append("        <Nm>").append(escape(request.getDebtorName() != null ? request.getDebtorName() : sourceVa.getVaName())).append("</Nm>\n");
        xml.append("      </InitgPty>\n");
        xml.append("    </GrpHdr>\n");

        // Payment Information
        xml.append("    <PmtInf>\n");
        xml.append("      <PmtInfId>").append(escape(request.getPaymentInfoId() != null ? request.getPaymentInfoId() : messageId)).append("</PmtInfId>\n");
        xml.append("      <PmtMtd>TRF</PmtMtd>\n");
        xml.append("      <NbOfTxs>").append(transactions.size()).append("</NbOfTxs>\n");
        xml.append("      <CtrlSum>").append(totalAmount).append("</CtrlSum>\n");

        // Requested Execution Date
        LocalDate execDate = request.getRequestedExecutionDate() != null ?
                request.getRequestedExecutionDate() : LocalDate.now();
        xml.append("      <ReqdExctnDt>\n");
        xml.append("        <Dt>").append(execDate.format(ISO_DATE)).append("</Dt>\n");
        xml.append("      </ReqdExctnDt>\n");

        // Debtor
        xml.append("      <Dbtr>\n");
        xml.append("        <Nm>").append(escape(request.getDebtorName() != null ? request.getDebtorName() : sourceVa.getVaName())).append("</Nm>\n");
        xml.append("      </Dbtr>\n");

        xml.append("      <DbtrAcct>\n");
        xml.append("        <Id>\n");
        xml.append("          <IBAN>").append(escape(request.getDebtorAccount() != null ? request.getDebtorAccount() : sourceVa.getViban())).append("</IBAN>\n");
        xml.append("        </Id>\n");
        xml.append("      </DbtrAcct>\n");

        xml.append("      <DbtrAgt>\n");
        xml.append("        <FinInstnId>\n");
        xml.append("          <BICFI>").append(escape(request.getDebtorBic() != null ? request.getDebtorBic() : "BANKAEXX")).append("</BICFI>\n");
        xml.append("        </FinInstnId>\n");
        xml.append("      </DbtrAgt>\n");

        // Add each credit transfer
        for (int i = 0; i < transactions.size(); i++) {
            Transaction txn = transactions.get(i);
            PaymentInstruction inst = request.getInstructions().get(i);

            xml.append("      <CdtTrfTxInf>\n");
            xml.append("        <PmtId>\n");
            xml.append("          <InstrId>").append(escape(inst.getInstructionId() != null ? inst.getInstructionId() : txn.getReferenceNumber())).append("</InstrId>\n");
            xml.append("          <EndToEndId>").append(escape(inst.getEndToEndId() != null ? inst.getEndToEndId() : txn.getCorrelationId())).append("</EndToEndId>\n");
            xml.append("        </PmtId>\n");

            String currency = inst.getCurrency() != null ? inst.getCurrency() : sourceVa.getCurrencyCode();
            xml.append("        <Amt>\n");
            xml.append("          <InstdAmt Ccy=\"").append(currency).append("\">").append(inst.getAmount()).append("</InstdAmt>\n");
            xml.append("        </Amt>\n");

            if (inst.getCreditorBic() != null) {
                xml.append("        <CdtrAgt>\n");
                xml.append("          <FinInstnId>\n");
                xml.append("            <BICFI>").append(escape(inst.getCreditorBic())).append("</BICFI>\n");
                xml.append("          </FinInstnId>\n");
                xml.append("        </CdtrAgt>\n");
            }

            xml.append("        <Cdtr>\n");
            xml.append("          <Nm>").append(escape(inst.getCreditorName())).append("</Nm>\n");
            xml.append("        </Cdtr>\n");

            xml.append("        <CdtrAcct>\n");
            xml.append("          <Id>\n");
            xml.append("            <IBAN>").append(escape(inst.getCreditorAccount())).append("</IBAN>\n");
            xml.append("          </Id>\n");
            xml.append("        </CdtrAcct>\n");

            if (inst.getRemittanceInfo() != null) {
                xml.append("        <RmtInf>\n");
                xml.append("          <Ustrd>").append(escape(inst.getRemittanceInfo())).append("</Ustrd>\n");
                xml.append("        </RmtInf>\n");
            }

            xml.append("      </CdtTrfTxInf>\n");
        }

        xml.append("    </PmtInf>\n");
        xml.append("  </CstmrCdtTrfInitn>\n");
        xml.append("</Document>");

        return xml.toString();
    }

    // ========================================================================
    // pain.002 - Customer Payment Status Report
    // ========================================================================

    public String buildPain002(Transaction txn, PaymentStatusResponse status) {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pain.002.001.10\">\n");
        xml.append("  <CstmrPmtStsRpt>\n");

        // Group Header
        xml.append("    <GrpHdr>\n");
        xml.append("      <MsgId>").append(escape(status.getMessageId())).append("</MsgId>\n");
        xml.append("      <CreDtTm>").append(LocalDateTime.now().format(ISO_DATETIME)).append("</CreDtTm>\n");
        xml.append("    </GrpHdr>\n");

        // Original Group Information and Status
        xml.append("    <OrgnlGrpInfAndSts>\n");
        xml.append("      <OrgnlMsgId>").append(escape(status.getOriginalMessageId())).append("</OrgnlMsgId>\n");
        xml.append("      <OrgnlMsgNmId>pain.001.001.09</OrgnlMsgNmId>\n");
        xml.append("      <GrpSts>").append(status.getTransactionStatus()).append("</GrpSts>\n");
        xml.append("    </OrgnlGrpInfAndSts>\n");

        // Original Payment Information and Status
        xml.append("    <OrgnlPmtInfAndSts>\n");
        xml.append("      <OrgnlPmtInfId>").append(escape(status.getOriginalMessageId())).append("</OrgnlPmtInfId>\n");
        xml.append("      <PmtInfSts>").append(status.getTransactionStatus()).append("</PmtInfSts>\n");

        // Transaction Information and Status
        xml.append("      <TxInfAndSts>\n");
        if (status.getOriginalInstructionId() != null) {
            xml.append("        <OrgnlInstrId>").append(escape(status.getOriginalInstructionId())).append("</OrgnlInstrId>\n");
        }
        if (status.getOriginalEndToEndId() != null) {
            xml.append("        <OrgnlEndToEndId>").append(escape(status.getOriginalEndToEndId())).append("</OrgnlEndToEndId>\n");
        }
        xml.append("        <TxSts>").append(status.getTransactionStatus()).append("</TxSts>\n");

        if (status.getStatusReasonCode() != null) {
            xml.append("        <StsRsnInf>\n");
            xml.append("          <Rsn>\n");
            xml.append("            <Cd>").append(status.getStatusReasonCode()).append("</Cd>\n");
            xml.append("          </Rsn>\n");
            if (status.getStatusReasonDescription() != null) {
                xml.append("          <AddtlInf>").append(escape(status.getStatusReasonDescription())).append("</AddtlInf>\n");
            }
            xml.append("        </StsRsnInf>\n");
        }

        xml.append("      </TxInfAndSts>\n");
        xml.append("    </OrgnlPmtInfAndSts>\n");
        xml.append("  </CstmrPmtStsRpt>\n");
        xml.append("</Document>");

        return xml.toString();
    }

    // ========================================================================
    // camt.053 - Bank-to-Customer Statement
    // ========================================================================

    public String buildCamt053(VirtualAccount va, List<Transaction> transactions,
                                LocalDate fromDate, LocalDate toDate,
                                BigDecimal openingBalance, StatementResponse summary) {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:camt.053.001.08\">\n");
        xml.append("  <BkToCstmrStmt>\n");

        // Group Header
        xml.append("    <GrpHdr>\n");
        xml.append("      <MsgId>").append(escape(summary.getMessageId())).append("</MsgId>\n");
        xml.append("      <CreDtTm>").append(LocalDateTime.now().format(ISO_DATETIME)).append("</CreDtTm>\n");
        xml.append("    </GrpHdr>\n");

        // Statement
        xml.append("    <Stmt>\n");
        xml.append("      <Id>").append(escape(summary.getStatementId())).append("</Id>\n");
        xml.append("      <ElctrncSeqNb>1</ElctrncSeqNb>\n");
        xml.append("      <CreDtTm>").append(LocalDateTime.now().format(ISO_DATETIME)).append("</CreDtTm>\n");

        // Account
        xml.append("      <Acct>\n");
        xml.append("        <Id>\n");
        xml.append("          <IBAN>").append(escape(va.getViban())).append("</IBAN>\n");
        xml.append("        </Id>\n");
        xml.append("        <Ccy>").append(va.getCurrencyCode()).append("</Ccy>\n");
        xml.append("        <Nm>").append(escape(va.getVaName())).append("</Nm>\n");
        xml.append("      </Acct>\n");

        // Opening Balance
        xml.append("      <Bal>\n");
        xml.append("        <Tp><CdOrPrtry><Cd>OPBD</Cd></CdOrPrtry></Tp>\n");
        xml.append("        <Amt Ccy=\"").append(va.getCurrencyCode()).append("\">").append(openingBalance.abs()).append("</Amt>\n");
        xml.append("        <CdtDbtInd>").append(openingBalance.compareTo(BigDecimal.ZERO) >= 0 ? "CRDT" : "DBIT").append("</CdtDbtInd>\n");
        xml.append("        <Dt><Dt>").append(fromDate.format(ISO_DATE)).append("</Dt></Dt>\n");
        xml.append("      </Bal>\n");

        // Closing Balance
        xml.append("      <Bal>\n");
        xml.append("        <Tp><CdOrPrtry><Cd>CLBD</Cd></CdOrPrtry></Tp>\n");
        xml.append("        <Amt Ccy=\"").append(va.getCurrencyCode()).append("\">").append(va.getCurrentBalance().abs()).append("</Amt>\n");
        xml.append("        <CdtDbtInd>").append(va.getCurrentBalance().compareTo(BigDecimal.ZERO) >= 0 ? "CRDT" : "DBIT").append("</CdtDbtInd>\n");
        xml.append("        <Dt><Dt>").append(toDate.format(ISO_DATE)).append("</Dt></Dt>\n");
        xml.append("      </Bal>\n");

        // Transaction Summary
        xml.append("      <TxsSummry>\n");
        xml.append("        <TtlCdtNtries>\n");
        xml.append("          <NbOfNtries>").append(summary.getCreditCount()).append("</NbOfNtries>\n");
        xml.append("          <Sum>").append(summary.getCreditSum()).append("</Sum>\n");
        xml.append("        </TtlCdtNtries>\n");
        xml.append("        <TtlDbtNtries>\n");
        xml.append("          <NbOfNtries>").append(summary.getDebitCount()).append("</NbOfNtries>\n");
        xml.append("          <Sum>").append(summary.getDebitSum()).append("</Sum>\n");
        xml.append("        </TtlDbtNtries>\n");
        xml.append("      </TxsSummry>\n");

        // Entries
        for (Transaction txn : transactions) {
            xml.append("      <Ntry>\n");
            xml.append("        <Amt Ccy=\"").append(txn.getCurrencyCode()).append("\">").append(txn.getAmount()).append("</Amt>\n");
            xml.append("        <CdtDbtInd>").append(txn.isCredit() ? "CRDT" : "DBIT").append("</CdtDbtInd>\n");
            xml.append("        <Sts><Cd>BOOK</Cd></Sts>\n");
            xml.append("        <BookgDt><Dt>").append(txn.getTransactionDate().toLocalDate().format(ISO_DATE)).append("</Dt></BookgDt>\n");
            if (txn.getValueDate() != null) {
                xml.append("        <ValDt><Dt>").append(txn.getValueDate().format(ISO_DATE)).append("</Dt></ValDt>\n");
            }
            xml.append("        <AcctSvcrRef>").append(escape(txn.getReferenceNumber())).append("</AcctSvcrRef>\n");

            // Entry Details
            xml.append("        <NtryDtls>\n");
            xml.append("          <TxDtls>\n");
            xml.append("            <Refs>\n");
            xml.append("              <MsgId>").append(escape(txn.getReferenceNumber())).append("</MsgId>\n");
            if (txn.getCorrelationId() != null) {
                xml.append("              <EndToEndId>").append(escape(txn.getCorrelationId())).append("</EndToEndId>\n");
            }
            xml.append("            </Refs>\n");

            // Related Parties
            if (txn.getRemitterName() != null || txn.getBeneficiaryName() != null) {
                xml.append("            <RltdPties>\n");
                if (txn.getRemitterName() != null) {
                    xml.append("              <Dbtr><Pty><Nm>").append(escape(txn.getRemitterName())).append("</Nm></Pty></Dbtr>\n");
                }
                if (txn.getBeneficiaryName() != null) {
                    xml.append("              <Cdtr><Pty><Nm>").append(escape(txn.getBeneficiaryName())).append("</Nm></Pty></Cdtr>\n");
                }
                xml.append("            </RltdPties>\n");
            }

            // Remittance Info
            if (txn.getDescription() != null) {
                xml.append("            <RmtInf>\n");
                xml.append("              <Ustrd>").append(escape(txn.getDescription())).append("</Ustrd>\n");
                xml.append("            </RmtInf>\n");
            }

            xml.append("          </TxDtls>\n");
            xml.append("        </NtryDtls>\n");
            xml.append("      </Ntry>\n");
        }

        xml.append("    </Stmt>\n");
        xml.append("  </BkToCstmrStmt>\n");
        xml.append("</Document>");

        return xml.toString();
    }

    // ========================================================================
    // Helper Methods
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
}
