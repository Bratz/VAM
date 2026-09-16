package com.bank.vam.service.fileingest;

import com.bank.vam.entity.fileingest.RowStatus;
import com.bank.vam.entity.fileingest.StagedTransaction;
import com.bank.vam.repository.fileingest.StagedTransactionRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RowQuarantineServiceTest {

    private final StagedTransactionRepository repository = mock(StagedTransactionRepository.class);
    private final RowQuarantineService service = new RowQuarantineService(repository);

    @Test
    void marksValidRowsReadyAndInvalidRowsQuarantinedWithAReason() {
        UUID jobId = UUID.randomUUID();
        StagedTransaction valid = rowWith(new BigDecimal("100.00"), "AED", "VIBAN001");
        StagedTransaction negativeAmount = rowWith(new BigDecimal("-5.00"), "AED", "VIBAN002");
        StagedTransaction badCurrency = rowWith(new BigDecimal("10.00"), "DIRHAM", "VIBAN003");
        StagedTransaction missingViban = rowWith(new BigDecimal("10.00"), "AED", null);
        when(repository.findByIngestJobId(jobId)).thenReturn(List.of(valid, negativeAmount, badCurrency, missingViban));

        service.quarantineInvalidRows(jobId);

        assertThat(valid.getStatus()).isEqualTo(RowStatus.READY);
        assertThat(valid.getReason()).isNull();

        assertThat(negativeAmount.getStatus()).isEqualTo(RowStatus.QUARANTINED);
        assertThat(negativeAmount.getReason()).contains("positive");

        assertThat(badCurrency.getStatus()).isEqualTo(RowStatus.QUARANTINED);
        assertThat(badCurrency.getReason()).contains("3-letter");

        assertThat(missingViban.getStatus()).isEqualTo(RowStatus.QUARANTINED);
        assertThat(missingViban.getReason()).contains("account");

        verify(repository).saveAll(any());
    }

    private StagedTransaction rowWith(BigDecimal amount, String currency, String viban) {
        StagedTransaction row = new StagedTransaction();
        row.setAmount(amount);
        row.setCurrency(currency);
        row.setTargetAccountReference(viban);
        return row;
    }
}
