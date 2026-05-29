package com.bank.vam.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Active market profile for this deployment.
 *
 * Resolution order:
 *  1. {@code vam.market.profile} (or {@code VAM_MARKET_PROFILE} env var) selects one of
 *     {@link MarketProfile#BUILTINS} — UAE, KSA, UK, EU, US, SG. Defaults to UAE.
 *  2. Any of the individual {@code vam.market.*} fields override the bundled profile.
 *
 * Usage: inject this bean and call {@link #getDefaultCurrency()} / {@link #getIbanCountryCode()}
 * etc. instead of hardcoding "AED" / "AE" / "EIBOR".
 */
@Slf4j
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "vam.market")
public class MarketProfileProperties {

    /** Selected built-in profile name (UAE / KSA / UK / EU / US / SG). */
    private String profile = "UAE";

    // Individual overrides — null means "use whatever the bundled profile says".
    private String defaultCurrency;
    private String defaultCountryCode;
    private String defaultLocale;
    private String defaultTimezone;
    private String defaultBaseRateType;
    private MarketProfile.Weekend weekend;
    private String homeBankBic;
    private String homeBankName;
    private String ibanCountryCode;
    private List<String> suggestedCurrencies;
    private String sampleCorporateName;
    private String sampleIban;
    private String sampleAccountNumber;
    private String sampleTaxId;

    private MarketProfile resolved;

    @PostConstruct
    void init() {
        MarketProfile base = MarketProfile.resolve(profile);
        this.resolved = MarketProfile.builder()
                .code(base.getCode())
                .displayName(base.getDisplayName())
                .defaultCurrency(coalesce(defaultCurrency, base.getDefaultCurrency()))
                .defaultCountryCode(coalesce(defaultCountryCode, base.getDefaultCountryCode()))
                .defaultLocale(coalesce(defaultLocale, base.getDefaultLocale()))
                .defaultTimezone(coalesce(defaultTimezone, base.getDefaultTimezone()))
                .defaultBaseRateType(coalesce(defaultBaseRateType, base.getDefaultBaseRateType()))
                .weekend(weekend != null ? weekend : base.getWeekend())
                .homeBankBic(coalesce(homeBankBic, base.getHomeBankBic()))
                .homeBankName(coalesce(homeBankName, base.getHomeBankName()))
                .ibanCountryCode(coalesce(ibanCountryCode, base.getIbanCountryCode()))
                .suggestedCurrencies(suggestedCurrencies != null && !suggestedCurrencies.isEmpty()
                        ? suggestedCurrencies : base.getSuggestedCurrencies())
                .sampleCorporateName(coalesce(sampleCorporateName, base.getSampleCorporateName()))
                .sampleIban(coalesce(sampleIban, base.getSampleIban()))
                .sampleAccountNumber(coalesce(sampleAccountNumber, base.getSampleAccountNumber()))
                .sampleTaxId(coalesce(sampleTaxId, base.getSampleTaxId()))
                .build();
        log.info("Active market profile: {} (currency={}, country={}, locale={}, baseRate={}, homeBank={})",
                resolved.getCode(), resolved.getDefaultCurrency(), resolved.getDefaultCountryCode(),
                resolved.getDefaultLocale(), resolved.getDefaultBaseRateType(), resolved.getHomeBankName());
    }

    // ------------------------------------------------------------------------
    // Convenience accessors (preferred — code reads
    //    marketProfile.getDefaultCurrency()
    // instead of
    //    marketProfile.getResolved().getDefaultCurrency()
    // )
    // ------------------------------------------------------------------------
    public String getDefaultCurrency()      { return resolved.getDefaultCurrency(); }
    public String getDefaultCountryCode()   { return resolved.getDefaultCountryCode(); }
    public String getDefaultLocale()        { return resolved.getDefaultLocale(); }
    public String getDefaultTimezone()      { return resolved.getDefaultTimezone(); }
    public String getDefaultBaseRateType()  { return resolved.getDefaultBaseRateType(); }
    public MarketProfile.Weekend getActiveWeekend() { return resolved.getWeekend(); }
    public String getActiveHomeBankBic()    { return resolved.getHomeBankBic(); }
    public String getActiveHomeBankName()   { return resolved.getHomeBankName(); }
    public String getActiveIbanCountryCode(){ return resolved.getIbanCountryCode(); }
    public List<String> getActiveSuggestedCurrencies() { return resolved.getSuggestedCurrencies(); }
    public String getActiveProfileCode()    { return resolved.getCode(); }
    public String getActiveDisplayName()    { return resolved.getDisplayName(); }
    public String getActiveSampleCorporateName() { return resolved.getSampleCorporateName(); }
    public String getActiveSampleIban()     { return resolved.getSampleIban(); }
    public String getActiveSampleAccountNumber() { return resolved.getSampleAccountNumber(); }
    public String getActiveSampleTaxId()    { return resolved.getSampleTaxId(); }

    private static String coalesce(String override, String fallback) {
        return override != null && !override.isBlank() ? override : fallback;
    }
}
