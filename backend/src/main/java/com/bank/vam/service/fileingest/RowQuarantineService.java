package com.bank.vam.service.fileingest;

import com.bank.vam.entity.fileingest.RowStatus;
import com.bank.vam.entity.fileingest.StagedTransaction;
import com.bank.vam.repository.fileingest.StagedTransactionRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Validates each staged row independently so one bad row never blocks its
 * siblings — a row that fails goes to QUARANTINED with a human-readable
 * reason, everything else proceeds to READY.
 */
@Component
public class RowQuarantineService {

    private final StagedTransactionRepository repository;

    public RowQuarantineService(StagedTransactionRepository repository) {
        this.repository = repository;
    }

    public void quarantineInvalidRows(UUID ingestJobId) {
        List<StagedTransaction> rows = repository.findByIngestJobId(ingestJobId);
        for (StagedTransaction row : rows) {
            String reason = validate(row);
            if (reason != null) {
                row.setStatus(RowStatus.QUARANTINED);
                row.setReason(reason);
            } else {
                row.setStatus(RowStatus.READY);
            }
        }
        repository.saveAll(rows);
    }

    /** Returns a human-readable quarantine reason, or null if the row is valid. */
    private String validate(StagedTransaction row) {
        if (row.getAmount() == null || row.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            return "Amount must be positive, was " + row.getAmount();
        }
        if (row.getCurrency() == null || row.getCurrency().length() != 3) {
            return "Currency must be a 3-letter code, was \"" + row.getCurrency() + "\"";
        }
        if (row.getTargetAccountReference() == null || row.getTargetAccountReference().isBlank()) {
            return "No target account/VIBAN reference on this row";
        }
        return null;
    }
}
