package com.bank.vam.fx.exposure;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Hedge policy bands per corporate. Every corporate gets the layered default on first use, so a
 * corporate onboarded after startup is never silently unpoliced.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HedgePolicyService {

    private final FxPolicyBandRepository bands;

    /** Layered hedging: cover most of what settles soon, progressively less of what is far out. */
    static List<FxPolicyBand> defaultBands(UUID corporateId) {
        return List.of(
                band(corporateId, ExposureBucket.D0_30, "0.75", "1.00"),
                band(corporateId, ExposureBucket.D31_60, "0.50", "0.80"),
                band(corporateId, ExposureBucket.D61_90, "0.40", "0.70"),
                band(corporateId, ExposureBucket.D91_180, "0.25", "0.50"),
                band(corporateId, ExposureBucket.D181_PLUS, "0.00", "0.25"));
    }

    /** @return the number of bands created (0 when the corporate already has a policy) */
    @Transactional
    public int ensureDefaults(UUID corporateId) {
        if (bands.existsByCorporateId(corporateId)) {
            return 0;
        }
        try {
            return bands.saveAll(defaultBands(corporateId)).size();
        } catch (DataIntegrityViolationException e) {
            // A concurrent request created the same defaults first; that is the outcome we wanted.
            log.debug("FX policy defaults for {} created concurrently", corporateId);
            return 0;
        }
    }

    public List<FxPolicyBand> bandsFor(UUID corporateId) {
        ensureDefaults(corporateId);
        return bands.findByCorporateId(corporateId);
    }

    private static FxPolicyBand band(UUID corporateId, ExposureBucket bucket, String min, String max) {
        FxPolicyBand b = com.bank.vam.entity.BaseEntity.systemDefault(new FxPolicyBand());
        b.setCorporateId(corporateId);
        b.setCurrency(FxPolicyBand.ANY);
        b.setBucket(bucket);
        b.setMinHedgeRatio(new BigDecimal(min));
        b.setMaxHedgeRatio(new BigDecimal(max));
        return b;
    }
}
