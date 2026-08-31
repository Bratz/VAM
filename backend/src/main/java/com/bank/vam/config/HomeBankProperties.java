package com.bank.vam.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Home Bank configuration — identifies this deployment's own bank.
 *
 * Used to distinguish home-bank-held mirror VAs (eligible for notional pool
 * membership) from external-bank shadows (sweep sources only). Bound from
 * {@code vam.home-bank.*} in application.yml.
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "vam.home-bank")
public class HomeBankProperties {

    /** SWIFT/BIC of the home bank (e.g. "HOMEBANKXXX"). */
    private String bic;

    // The home-bank display NAME is intentionally NOT a config property.
    // It is resolved from the actual account data by this BIC (see
    // VirtualAccountRepository.findBankNameByBic / PhysicalAccountController)
    // so the name is always consistent with the BIC. A static name field
    // here previously produced a stale wrong name whenever only the BIC was
    // overridden for a per-geography demo.

    public boolean matches(String otherBic) {
        return bic != null && otherBic != null && bic.equalsIgnoreCase(otherBic);
    }
}
