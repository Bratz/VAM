package com.bank.vam.service.treasury.rejection;

import com.bank.vam.entity.treasury.RejectionCodeConfig;
import com.bank.vam.entity.treasury.SweepInstruction.RejectionCategory;
import com.bank.vam.repository.treasury.RejectionCodeConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Classifies rail rejection codes into RECOVERABLE / UNRECOVERABLE.
 *
 * Lookup table-driven so ops can add new codes at runtime without a deploy.
 * If a code is unknown, returns RECOVERABLE (safer default — does not auto-pause
 * the rule on an unclassified code; surfaces in the dashboard for triage).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RejectionCodeRegistry {

    private final RejectionCodeConfigRepository repository;

    @Transactional(readOnly = true)
    public RejectionCategory classify(String code) {
        if (code == null || code.isBlank()) {
            return RejectionCategory.RECOVERABLE;
        }
        Optional<RejectionCodeConfig> row = repository.findByCode(code.trim().toUpperCase());
        if (row.isEmpty()) {
            log.warn("Unknown rejection code '{}' — defaulting to RECOVERABLE", code);
            return RejectionCategory.RECOVERABLE;
        }
        return row.get().getCategory();
    }
}
