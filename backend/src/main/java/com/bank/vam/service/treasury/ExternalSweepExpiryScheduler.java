package com.bank.vam.service.treasury;

import com.bank.vam.entity.treasury.SweepInstruction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * External-bank sweeps D7, R16: once a day, a leg the other bank's statement has not confirmed within 2 business days of
 * its execution date fails (EXPIRED) and its booking is reversed, each in its own transaction, unless a stored statement
 * already shows the debit ({@code fail} confirms it then: the intake hook may have failed). Runs in the evening, after
 * the morning's end-of-day statements for the last day of the window. Like every scheduled job here it only runs when
 * {@code vam.scheduling.enabled} is on.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class ExternalSweepExpiryScheduler {

    private final ExternalSweepService externalSweeps;

    @Scheduled(cron = "${vam.sweeps.external-expiry-cron:0 0 18 * * *}")
    void expireOverdue() {
        for (SweepInstruction i : externalSweeps.overdue(LocalDate.now())) {
            try {
                externalSweeps.fail(i.getId(), SweepInstruction.InstructionStatus.EXPIRED, null);
            } catch (Exception e) {
                log.error("External sweep {} not expired", i.getExternalReference(), e);
            }
        }
    }
}
