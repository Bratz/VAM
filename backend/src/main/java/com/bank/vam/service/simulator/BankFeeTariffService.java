package com.bank.vam.service.simulator;

import com.bank.vam.dto.simulator.BankFeeTariffDto;
import com.bank.vam.entity.simulator.BankFeeTariff;
import com.bank.vam.repository.simulator.BankFeeTariffRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves effective bank-fee tariffs for the Phase-2 Bank-fees score line.
 * Read-only reference data; no live-table writes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
/** @deprecated 2026-05-16 — Bank-fees rewired onto ChargeConfiguration
 *  (tax-charges); this service is unwired. Retained, not deleted. */
@Deprecated
public class BankFeeTariffService {

    private final BankFeeTariffRepository tariffRepository;

    /**
     * Effective tariffs for {@code bankCodes} as of {@code asOf} (defaults to
     * today). When effective windows overlap, the most recent
     * {@code effectiveFrom} wins per (bankCode, paymentRail) — the repository
     * query already orders {@code effectiveFrom DESC}, so the first row seen
     * for a key is the winner.
     */
    @Transactional(readOnly = true)
    public List<BankFeeTariffDto> getEffectiveTariffs(
            Collection<String> bankCodes, LocalDate asOf) {
        if (bankCodes == null || bankCodes.isEmpty()) {
            return List.of();
        }
        LocalDate when = asOf != null ? asOf : LocalDate.now();
        Map<String, BankFeeTariffDto> latestPerKey = new LinkedHashMap<>();
        for (BankFeeTariff t : tariffRepository.findEffective(bankCodes, when)) {
            String key = t.getBankCode() + '|' + t.getPaymentRail();
            latestPerKey.putIfAbsent(key, toDto(t));
        }
        return List.copyOf(latestPerKey.values());
    }

    private BankFeeTariffDto toDto(BankFeeTariff t) {
        return BankFeeTariffDto.builder()
                .bankCode(t.getBankCode())
                .paymentRail(t.getPaymentRail())
                .feeCurrency(t.getFeeCurrency())
                .feeFixed(t.getFeeFixed())
                .feeBps(t.getFeeBps())
                .effectiveFrom(t.getEffectiveFrom())
                .effectiveTo(t.getEffectiveTo())
                .source(t.getSource())
                .build();
    }
}
