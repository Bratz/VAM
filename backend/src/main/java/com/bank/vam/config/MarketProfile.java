package com.bank.vam.config;

import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.Map;

/**
 * A bundled market profile — the set of regional defaults the platform falls back
 * to when callers don't supply a value (currency, country, locale, base rate, etc.).
 *
 * Six profiles ship in code so a deployment can switch market by setting the
 * single env var {@code VAM_MARKET_PROFILE} (UAE / KSA / UK / EU / US / SG).
 * Individual fields can still be overridden in {@code application.yml} under
 * {@code vam.market.*}.
 *
 * Why an enum-keyed map instead of YAML profiles? Faster bootstrap, no soup of
 * nested YAML, and every supported market is discoverable by Ctrl+F in the source.
 * New markets are a one-row addition.
 */
@Getter
@Builder
public class MarketProfile {

    /** Short, stable identifier for the profile (UAE, KSA, UK, ...). */
    private final String code;

    /** Display name shown in admin UIs. */
    private final String displayName;

    /** ISO 4217 default currency code (AED, SAR, GBP, ...). */
    private final String defaultCurrency;

    /** ISO 3166-1 alpha-2 country code (AE, SA, GB, DE, US, SG). */
    private final String defaultCountryCode;

    /** BCP 47 locale tag (en-AE, en-SA, en-GB, de-DE, en-US, en-SG). */
    private final String defaultLocale;

    /** IANA timezone (Asia/Dubai, Asia/Riyadh, Europe/London, ...). */
    private final String defaultTimezone;

    /** Default reference-rate label used for intercompany interest (EIBOR, SAIBOR, SONIA, EURIBOR, SOFR, SORA). */
    private final String defaultBaseRateType;

    /** Working-week weekend pattern — drives bank-calendar logic. */
    private final Weekend weekend;

    /** Default home-bank BIC for demo data. */
    private final String homeBankBic;

    /** Default home-bank display name. */
    private final String homeBankName;

    /** ISO 3166 country prefix used in synthetic IBAN generation. */
    private final String ibanCountryCode;

    /** Suggested currency picker order (active first). */
    private final List<String> suggestedCurrencies;

    // ------------------------------------------------------------------------
    // Demo bundle — used by services that surface sample/seed data so the demo
    // looks native to the active market (party records, sample bank accounts,
    // tax ids, etc.). Real customer data is unaffected.
    // ------------------------------------------------------------------------

    /** Sample corporate name for demo seeds (e.g. "Emirates Steel Industries PJSC"). */
    private final String sampleCorporateName;

    /** Sample IBAN for demo seeds (e.g. "AE070331234567890123456"). */
    private final String sampleIban;

    /** Sample bank-account number for demo seeds. */
    private final String sampleAccountNumber;

    /** Sample tax-ID label + value for demo seeds (e.g. "TRN100123456"). */
    private final String sampleTaxId;

    public enum Weekend {
        /** Saturday + Sunday (global default, UK/EU/US/SG). */
        SAT_SUN,
        /** Friday + Saturday (some legacy GCC). */
        FRI_SAT,
        /** Saturday + Sunday in current GCC after reform (UAE, KSA today). */
        SUN_THU  // kept for completeness; UAE/KSA today operate Sun-Thu = Fri+Sat off
    }

    // ------------------------------------------------------------------------
    // Built-in profiles
    // ------------------------------------------------------------------------

    public static final MarketProfile UAE = MarketProfile.builder()
            .code("UAE")
            .displayName("United Arab Emirates")
            .defaultCurrency("AED")
            .defaultCountryCode("AE")
            .defaultLocale("en-AE")
            .defaultTimezone("Asia/Dubai")
            .defaultBaseRateType("EIBOR")
            .weekend(Weekend.SAT_SUN)         // post-2022 reform — UAE works Mon-Fri now
            .homeBankBic("EABORAEAD")
            .homeBankName("Emirates NBD")
            .ibanCountryCode("AE")
            .suggestedCurrencies(List.of("AED", "USD", "EUR", "GBP", "SAR", "SGD"))
            .sampleCorporateName("Emirates Steel Industries PJSC")
            .sampleIban("AE070331234567890123456")
            .sampleAccountNumber("0331234567890")
            .sampleTaxId("TRN100123456")
            .build();

    public static final MarketProfile KSA = MarketProfile.builder()
            .code("KSA")
            .displayName("Saudi Arabia")
            .defaultCurrency("SAR")
            .defaultCountryCode("SA")
            .defaultLocale("en-SA")
            .defaultTimezone("Asia/Riyadh")
            .defaultBaseRateType("SAIBOR")
            .weekend(Weekend.FRI_SAT)
            .homeBankBic("RJHISARI")
            .homeBankName("Al Rajhi Bank")
            .ibanCountryCode("SA")
            .suggestedCurrencies(List.of("SAR", "USD", "EUR", "GBP", "AED"))
            .sampleCorporateName("Saudi Industrial Investment Group")
            .sampleIban("SA0380000000608010167519")
            .sampleAccountNumber("608010167519")
            .sampleTaxId("VAT300012345600003")
            .build();

    public static final MarketProfile UK = MarketProfile.builder()
            .code("UK")
            .displayName("United Kingdom")
            .defaultCurrency("GBP")
            .defaultCountryCode("GB")
            .defaultLocale("en-GB")
            .defaultTimezone("Europe/London")
            .defaultBaseRateType("SONIA")
            .weekend(Weekend.SAT_SUN)
            .homeBankBic("HBUKGB4B")
            .homeBankName("HSBC UK")
            .ibanCountryCode("GB")
            .suggestedCurrencies(List.of("GBP", "EUR", "USD", "CHF", "JPY"))
            .sampleCorporateName("Thames Trading Holdings Plc")
            .sampleIban("GB29HBUK40051512345678")
            .sampleAccountNumber("12345678")
            .sampleTaxId("GB123456789")
            .build();

    public static final MarketProfile EU = MarketProfile.builder()
            .code("EU")
            .displayName("European Union (Germany)")
            .defaultCurrency("EUR")
            .defaultCountryCode("DE")
            .defaultLocale("de-DE")
            .defaultTimezone("Europe/Berlin")
            .defaultBaseRateType("EURIBOR")
            .weekend(Weekend.SAT_SUN)
            .homeBankBic("DEUTDEFF")
            .homeBankName("Deutsche Bank")
            .ibanCountryCode("DE")
            .suggestedCurrencies(List.of("EUR", "GBP", "USD", "CHF", "SEK"))
            .sampleCorporateName("Rheinland Industrie GmbH")
            .sampleIban("DE89370400440532013000")
            .sampleAccountNumber("0532013000")
            .sampleTaxId("DE123456789")
            .build();

    public static final MarketProfile US = MarketProfile.builder()
            .code("US")
            .displayName("United States")
            .defaultCurrency("USD")
            .defaultCountryCode("US")
            .defaultLocale("en-US")
            .defaultTimezone("America/New_York")
            .defaultBaseRateType("SOFR")
            .weekend(Weekend.SAT_SUN)
            .homeBankBic("CHASUS33")
            .homeBankName("JPMorgan Chase")
            .ibanCountryCode("US")             // US has no IBAN; treat as account-number-only at write sites
            .suggestedCurrencies(List.of("USD", "EUR", "GBP", "CAD", "JPY"))
            .sampleCorporateName("Atlantic Industrial Holdings, Inc.")
            .sampleIban("")                    // US uses ABA routing + account, not IBAN
            .sampleAccountNumber("000123456789")
            .sampleTaxId("EIN12-3456789")
            .build();

    public static final MarketProfile SG = MarketProfile.builder()
            .code("SG")
            .displayName("Singapore")
            .defaultCurrency("SGD")
            .defaultCountryCode("SG")
            .defaultLocale("en-SG")
            .defaultTimezone("Asia/Singapore")
            .defaultBaseRateType("SORA")
            .weekend(Weekend.SAT_SUN)
            .homeBankBic("DBSSSGSG")
            .homeBankName("DBS Bank")
            .ibanCountryCode("SG")             // SG has no IBAN; ditto US
            .suggestedCurrencies(List.of("SGD", "USD", "EUR", "JPY", "AUD"))
            .sampleCorporateName("Marina Bay Trading Pte Ltd")
            .sampleIban("")                    // SG uses bank-account format, not IBAN
            .sampleAccountNumber("0011234567891")
            .sampleTaxId("GST200012345M")
            .build();

    public static final Map<String, MarketProfile> BUILTINS = Map.of(
            "UAE", UAE,
            "KSA", KSA,
            "UK",  UK,
            "EU",  EU,
            "US",  US,
            "SG",  SG
    );

    public static MarketProfile resolve(String code) {
        if (code == null || code.isBlank()) return UAE;
        MarketProfile p = BUILTINS.get(code.trim().toUpperCase());
        return p != null ? p : UAE;
    }
}
