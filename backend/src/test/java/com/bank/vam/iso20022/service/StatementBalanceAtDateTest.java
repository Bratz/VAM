package com.bank.vam.iso20022.service;

import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.iso20022.dto.Iso20022StatementDto.Camt053GenerateRequest;
import com.bank.vam.iso20022.dto.Iso20022StatementDto.Camt053Response;
import com.bank.vam.repository.TransactionRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Opening and closing balances come from the ledger at the period's edges, not today's balance. */
class StatementBalanceAtDateTest {

    private final TransactionRepository txns = mock(TransactionRepository.class);
    private final VirtualAccountRepository vas = mock(VirtualAccountRepository.class);
    private final Iso20022StatementService service = new Iso20022StatementService(txns, vas, new ObjectMapper());

    private final UUID vaId = UUID.randomUUID();
    private final LocalDate from = LocalDate.of(2026, 1, 1);
    private final LocalDate to = LocalDate.of(2026, 1, 31);

    private Camt053Response generate() {
        VirtualAccount va = VirtualAccount.builder().vaNumber("VA-0001").currencyCode("USD")
                .currentBalance(new BigDecimal("999")).availableBalance(new BigDecimal("999")).build();
        va.setId(vaId);
        when(vas.findById(vaId)).thenReturn(Optional.of(va));
        when(txns.findByVaIdAndTransactionDateBetween(eq(vaId), any(), any(), any())).thenReturn(new PageImpl<>(List.of()));
        return service.generateCamt053(Camt053GenerateRequest.builder().vaId(vaId).fromDate(from).toDate(to).build());
    }

    @Test
    void balancesAreTheLedgerBalancesAtThePeriodEdges() {
        when(txns.findLedgerBalanceBefore(vaId, from.atStartOfDay())).thenReturn(Optional.of(new BigDecimal("100")));
        when(txns.findLedgerBalanceBefore(vaId, to.plusDays(1).atStartOfDay())).thenReturn(Optional.of(new BigDecimal("250")));

        Camt053Response r = generate();

        assertEquals(new BigDecimal("100"), r.getSummary().getOpeningBalance());
        assertEquals(new BigDecimal("250"), r.getSummary().getClosingBalance());
        assertNull(r.getSummary().getAvailableBalance(), "a past period has no available balance to report");
    }

    @Test
    void noLedgerHistoryMeansNoBalanceRatherThanTodaysOrZero() {
        when(txns.findLedgerBalanceBefore(eq(vaId), any())).thenReturn(Optional.empty());

        Camt053Response r = generate();

        assertNull(r.getSummary().getOpeningBalance());
        assertNull(r.getSummary().getClosingBalance());
    }
}
