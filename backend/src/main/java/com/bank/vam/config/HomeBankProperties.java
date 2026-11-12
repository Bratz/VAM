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
        return sameBank(bic, otherBic);
    }

    /**
     * Same institution and branch: an 8-character BIC is the head office, the same as its 11-character form ending
     * "XXX" (HBUKGB4B == HBUKGB4BXXX). A literal compare treated the home bank's own accounts as external.
     */
    public static boolean sameBank(String a, String b) {
        return a != null && b != null && bic11(a).equals(bic11(b));
    }

    private static String bic11(String bic) {
        String s = bic.trim().toUpperCase();
        return s.length() == 8 ? s + "XXX" : s;
    }
}
