package com.bank.vam.dto.fileingest;

import com.bank.vam.entity.fileingest.RowStatus;
import com.bank.vam.entity.fileingest.StagedTransaction;

import java.math.BigDecimal;
import java.util.UUID;

public record StagedRowResponse(
        int sourceRowNumber,
        RowStatus status,
        String reason,
        BigDecimal amount,
        String currency,
        String targetAccountReference,
        UUID processedEntityId
) {
    public static StagedRowResponse from(StagedTransaction row) {
        return new StagedRowResponse(
                row.getSourceRowNumber(),
                row.getStatus(),
                row.getReason(),
                row.getAmount(),
                row.getCurrency(),
                row.getTargetAccountReference(),
                row.getProcessedEntityId()
        );
    }
}
