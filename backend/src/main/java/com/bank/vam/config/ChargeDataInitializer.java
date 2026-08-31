package com.bank.vam.config;

import com.bank.vam.entity.tax.ChargeConfiguration;
import com.bank.vam.entity.tax.ChargeConfiguration.ChargeCategory;
import com.bank.vam.entity.tax.ChargeConfiguration.ChargeStatus;
import com.bank.vam.entity.tax.ChargeConfiguration.ChargeType;
import com.bank.vam.repository.tax.ChargeConfigurationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Data Initializer for Charge Configurations.
 *
 * Seeds essential charge configurations on application startup if they don't exist.
 *
 * IMPORTANT: Charge codes MUST match exactly what TransactionService expects:
 * - POBO_FEE (not SVC_POBO)
 * - SWIFT_PAYMENT_FEE (not SWIFT_STD)
 * - etc.
 *
 * This ensures that treasury operations (POBO, IHB, Transfers) have proper
 * fee configurations from the start.
 */
@Slf4j
@Component
@Order(100) // Run after other initializers
@RequiredArgsConstructor
public class ChargeDataInitializer implements ApplicationRunner {

    private final ChargeConfigurationRepository chargeConfigRepository;
    private final MarketProfileProperties marketProfile;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        log.info("Initializing charge configurations (currency={})...", marketProfile.getDefaultCurrency());

        int created = 0;

        // ================================================================
        // TRANSFER FEES - Used by TransactionService for internal transfers
        // ================================================================

        // Same Program Transfer Fee
        if (createIfNotExists("SAME_PROGRAM_TRANSFER_FEE", ChargeConfiguration.builder()
                .chargeCode("SAME_PROGRAM_TRANSFER_FEE")
                .chargeName("Same Program Transfer Fee")
                .description("Fee for transfers within the same program/structure.")
                .chargeType(ChargeType.TRANSACTION_FEE)
                .chargeCategory(ChargeCategory.PERCENTAGE)
                .percentageRate(new BigDecimal("0.05"))
                .minimumCharge(new BigDecimal("5.00"))
                .maximumCharge(new BigDecimal("50.00"))
                .currencyCode(marketProfile.getDefaultCurrency())
                .isDomestic(true)
                .isCrossBorder(false)
                .waiverThreshold(new BigDecimal("1000000"))  // Waive for large transfers
                .effectiveFrom(LocalDate.now())
                .status(ChargeStatus.ACTIVE)
                .build())) created++;

        // Cross Program Transfer Fee
        if (createIfNotExists("CROSS_PROGRAM_TRANSFER_FEE", ChargeConfiguration.builder()
                .chargeCode("CROSS_PROGRAM_TRANSFER_FEE")
                .chargeName("Cross Program Transfer Fee")
                .description("Fee for transfers between different programs/structures.")
                .chargeType(ChargeType.TRANSACTION_FEE)
                .chargeCategory(ChargeCategory.PERCENTAGE)
                .percentageRate(new BigDecimal("0.10"))
                .minimumCharge(new BigDecimal("10.00"))
                .maximumCharge(new BigDecimal("100.00"))
                .currencyCode(marketProfile.getDefaultCurrency())
                .isDomestic(true)
                .isCrossBorder(false)
                .effectiveFrom(LocalDate.now())
                .status(ChargeStatus.ACTIVE)
                .build())) created++;

        // Intercompany Transfer Fee
        if (createIfNotExists("INTERCOMPANY_TRANSFER_FEE", ChargeConfiguration.builder()
                .chargeCode("INTERCOMPANY_TRANSFER_FEE")
                .chargeName("Intercompany Transfer Fee")
                .description("Fee for transfers between legal entities within the same corporate.")
                .chargeType(ChargeType.TRANSACTION_FEE)
                .chargeCategory(ChargeCategory.PERCENTAGE)
                .percentageRate(new BigDecimal("0.15"))
                .minimumCharge(new BigDecimal("15.00"))
                .maximumCharge(new BigDecimal("150.00"))
                .currencyCode(marketProfile.getDefaultCurrency())
                .isDomestic(true)
                .isCrossBorder(false)
                .waiverForVip(true)  // Treasury Centers may get waiver
                .effectiveFrom(LocalDate.now())
                .status(ChargeStatus.ACTIVE)
                .build())) created++;

        // Cross-Border Intercompany Fee
        if (createIfNotExists("CROSS_BORDER_INTERCOMPANY_FEE", ChargeConfiguration.builder()
                .chargeCode("CROSS_BORDER_INTERCOMPANY_FEE")
                .chargeName("Cross-Border Intercompany Fee")
                .description("Additional fee for cross-border intercompany transfers.")
                .chargeType(ChargeType.TRANSACTION_FEE)
                .chargeCategory(ChargeCategory.FIXED)
                .fixedAmount(new BigDecimal("50.00"))
                .currencyCode(marketProfile.getDefaultCurrency())
                .isDomestic(false)
                .isCrossBorder(true)
                .effectiveFrom(LocalDate.now())
                .status(ChargeStatus.ACTIVE)
                .build())) created++;

        // FX Conversion Fee
        if (createIfNotExists("FX_CONVERSION_FEE", ChargeConfiguration.builder()
                .chargeCode("FX_CONVERSION_FEE")
                .chargeName("FX Conversion Fee")
                .description("Fee for foreign exchange conversion on cross-currency transfers.")
                .chargeType(ChargeType.FX_FEE)
                .chargeCategory(ChargeCategory.PERCENTAGE)
                .percentageRate(new BigDecimal("0.25"))
                .minimumCharge(new BigDecimal("10.00"))
                .maximumCharge(new BigDecimal("500.00"))
                .currencyCode(marketProfile.getDefaultCurrency())
                .isDomestic(false)
                .isCrossBorder(true)
                .effectiveFrom(LocalDate.now())
                .status(ChargeStatus.ACTIVE)
                .build())) created++;

        // Treasury Service Fee
        if (createIfNotExists("TREASURY_SERVICE_FEE", ChargeConfiguration.builder()
                .chargeCode("TREASURY_SERVICE_FEE")
                .chargeName("Treasury Service Fee")
                .description("Fee for treasury services on intercompany transactions.")
                .chargeType(ChargeType.SERVICE_FEE)
                .chargeCategory(ChargeCategory.PERCENTAGE)
                .percentageRate(new BigDecimal("0.10"))
                .minimumCharge(new BigDecimal("10.00"))
                .maximumCharge(new BigDecimal("200.00"))
                .currencyCode(marketProfile.getDefaultCurrency())
                .isDomestic(true)
                .isCrossBorder(true)
                .effectiveFrom(LocalDate.now())
                .status(ChargeStatus.ACTIVE)
                .build())) created++;

        // ================================================================
        // OUTBOUND PAYMENT FEES - Used by TransactionService for payments
        // ================================================================

        // Standard Outbound Payment Fee
        if (createIfNotExists("OUTBOUND_PAYMENT_FEE", ChargeConfiguration.builder()
                .chargeCode("OUTBOUND_PAYMENT_FEE")
                .chargeName("Outbound Payment Fee")
                .description("Standard fee for outbound payments to external beneficiaries.")
                .chargeType(ChargeType.PROCESSING_FEE)
                .chargeCategory(ChargeCategory.PERCENTAGE)
                .percentageRate(new BigDecimal("0.15"))
                .minimumCharge(new BigDecimal("15.00"))
                .maximumCharge(new BigDecimal("200.00"))
                .currencyCode(marketProfile.getDefaultCurrency())
                .isDomestic(true)
                .isCrossBorder(true)
                .effectiveFrom(LocalDate.now())
                .status(ChargeStatus.ACTIVE)
                .build())) created++;

        // SWIFT Payment Fee
        if (createIfNotExists("SWIFT_PAYMENT_FEE", ChargeConfiguration.builder()
                .chargeCode("SWIFT_PAYMENT_FEE")
                .chargeName("SWIFT Payment Fee")
                .description("Fee for SWIFT international wire transfers.")
                .chargeType(ChargeType.SWIFT_FEE)
                .chargeCategory(ChargeCategory.FIXED)
                .fixedAmount(new BigDecimal("35.00"))
                .currencyCode(marketProfile.getDefaultCurrency())
                .isDomestic(false)
                .isCrossBorder(true)
                .effectiveFrom(LocalDate.now())
                .status(ChargeStatus.ACTIVE)
                .build())) created++;

        // RTGS Payment Fee
        if (createIfNotExists("RTGS_PAYMENT_FEE", ChargeConfiguration.builder()
                .chargeCode("RTGS_PAYMENT_FEE")
                .chargeName("RTGS Payment Fee")
                .description("Fee for Real-Time Gross Settlement payments.")
                .chargeType(ChargeType.PROCESSING_FEE)
                .chargeCategory(ChargeCategory.FIXED)
                .fixedAmount(new BigDecimal("25.00"))
                .currencyCode(marketProfile.getDefaultCurrency())
                .isDomestic(true)
                .isCrossBorder(false)
                .effectiveFrom(LocalDate.now())
                .status(ChargeStatus.ACTIVE)
                .build())) created++;

        // SEPA Payment Fee
        if (createIfNotExists("SEPA_PAYMENT_FEE", ChargeConfiguration.builder()
                .chargeCode("SEPA_PAYMENT_FEE")
                .chargeName("SEPA Payment Fee")
                .description("Fee for SEPA (Single Euro Payments Area) transfers.")
                .chargeType(ChargeType.PROCESSING_FEE)
                .chargeCategory(ChargeCategory.FIXED)
                .fixedAmount(new BigDecimal("5.00"))
                .currencyCode("EUR")
                .isDomestic(false)
                .isCrossBorder(true)
                .effectiveFrom(LocalDate.now())
                .status(ChargeStatus.ACTIVE)
                .build())) created++;

        // ================================================================
        // POBO FEE - Critical for Pay-On-Behalf-Of transactions
        // ================================================================

        if (createIfNotExists("POBO_FEE", ChargeConfiguration.builder()
                .chargeCode("POBO_FEE")
                .chargeName("POBO Service Fee")
                .description("Service fee for Pay-On-Behalf-Of transactions. Charged to subsidiary for treasury payment services.")
                .chargeType(ChargeType.POBO_FEE)
                .chargeCategory(ChargeCategory.PERCENTAGE)
                .percentageRate(new BigDecimal("0.50"))
                .minimumCharge(new BigDecimal("25.00"))
                .maximumCharge(new BigDecimal("1000.00"))
                .currencyCode(marketProfile.getDefaultCurrency())
                .isDomestic(true)
                .isCrossBorder(true)
                .effectiveFrom(LocalDate.now())
                .status(ChargeStatus.ACTIVE)
                .build())) created++;

        // ================================================================
        // COLLECTION FEES - Inbound payment processing
        // ================================================================

        // Inbound Collection Fee
        if (createIfNotExists("INBOUND_COLLECTION_FEE", ChargeConfiguration.builder()
                .chargeCode("INBOUND_COLLECTION_FEE")
                .chargeName("Inbound Collection Fee")
                .description("Fee for processing inbound payment collections.")
                .chargeType(ChargeType.PROCESSING_FEE)
                .chargeCategory(ChargeCategory.PERCENTAGE)
                .percentageRate(new BigDecimal("0.10"))
                .minimumCharge(new BigDecimal("5.00"))
                .maximumCharge(new BigDecimal("100.00"))
                .currencyCode(marketProfile.getDefaultCurrency())
                .isDomestic(true)
                .isCrossBorder(true)
                .effectiveFrom(LocalDate.now())
                .status(ChargeStatus.ACTIVE)
                .build())) created++;

        // VIBAN Collection Fee (lower fee for virtual IBAN routing)
        if (createIfNotExists("VIBAN_COLLECTION_FEE", ChargeConfiguration.builder()
                .chargeCode("VIBAN_COLLECTION_FEE")
                .chargeName("VIBAN Collection Fee")
                .description("Fee for collections routed via Virtual IBAN. Lower than standard collection fee.")
                .chargeType(ChargeType.PROCESSING_FEE)
                .chargeCategory(ChargeCategory.PERCENTAGE)
                .percentageRate(new BigDecimal("0.05"))
                .minimumCharge(new BigDecimal("2.00"))
                .maximumCharge(new BigDecimal("50.00"))
                .currencyCode(marketProfile.getDefaultCurrency())
                .isDomestic(true)
                .isCrossBorder(true)
                .effectiveFrom(LocalDate.now())
                .status(ChargeStatus.ACTIVE)
                .build())) created++;

        // ROBO Collection Fee (Receive-On-Behalf-Of)
        if (createIfNotExists("ROBO_COLLECTION_FEE", ChargeConfiguration.builder()
                .chargeCode("ROBO_COLLECTION_FEE")
                .chargeName("ROBO Collection Fee")
                .description("Fee for Receive-On-Behalf-Of collections processed by treasury.")
                .chargeType(ChargeType.SERVICE_FEE)
                .chargeCategory(ChargeCategory.PERCENTAGE)
                .percentageRate(new BigDecimal("0.25"))
                .minimumCharge(new BigDecimal("10.00"))
                .maximumCharge(new BigDecimal("250.00"))
                .currencyCode(marketProfile.getDefaultCurrency())
                .isDomestic(true)
                .isCrossBorder(true)
                .effectiveFrom(LocalDate.now())
                .status(ChargeStatus.ACTIVE)
                .build())) created++;

        // COBO Collection Service Fee (Collect-On-Behalf-Of)
        if (createIfNotExists("COBO_SERVICE_FEE", ChargeConfiguration.builder()
                .chargeCode("COBO_SERVICE_FEE")
                .chargeName("COBO Collection Service Fee")
                .description("Fee for Collect-On-Behalf-Of collections where treasury collects payments on behalf of subsidiaries.")
                .chargeType(ChargeType.SERVICE_FEE)
                .chargeCategory(ChargeCategory.PERCENTAGE)
                .percentageRate(new BigDecimal("0.0015"))  // 0.15%
                .minimumCharge(new BigDecimal("5.00"))
                .maximumCharge(new BigDecimal("500.00"))
                .currencyCode(marketProfile.getDefaultCurrency())
                .isDomestic(true)
                .isCrossBorder(true)
                .effectiveFrom(LocalDate.now())
                .status(ChargeStatus.ACTIVE)
                .build())) created++;

        // ================================================================
        // TREASURY FEES - IHB, Netting, Pooling (for other services)
        // ================================================================

        // IHB Fee
        if (createIfNotExists("IHB_FEE", ChargeConfiguration.builder()
                .chargeCode("IHB_FEE")
                .chargeName("IHB Transaction Fee")
                .description("Fee for In-House Bank intercompany lending/borrowing transactions.")
                .chargeType(ChargeType.IHB_FEE)
                .chargeCategory(ChargeCategory.PERCENTAGE)
                .percentageRate(new BigDecimal("0.25"))
                .minimumCharge(new BigDecimal("10.00"))
                .maximumCharge(new BigDecimal("500.00"))
                .currencyCode(marketProfile.getDefaultCurrency())
                .isDomestic(true)
                .isCrossBorder(false)
                .effectiveFrom(LocalDate.now())
                .status(ChargeStatus.ACTIVE)
                .build())) created++;

        // Netting Fee
        if (createIfNotExists("NETTING_FEE", ChargeConfiguration.builder()
                .chargeCode("NETTING_FEE")
                .chargeName("Netting Cycle Fee")
                .description("Fee for participating in intercompany netting cycles.")
                .chargeType(ChargeType.NETTING_FEE)
                .chargeCategory(ChargeCategory.FIXED)
                .fixedAmount(new BigDecimal("50.00"))
                .currencyCode(marketProfile.getDefaultCurrency())
                .isDomestic(true)
                .isCrossBorder(false)
                .effectiveFrom(LocalDate.now())
                .status(ChargeStatus.ACTIVE)
                .build())) created++;

        // Pooling Fee
        if (createIfNotExists("POOLING_FEE", ChargeConfiguration.builder()
                .chargeCode("POOLING_FEE")
                .chargeName("Notional Pooling Fee")
                .description("Monthly fee for participation in notional pooling structures.")
                .chargeType(ChargeType.POOLING_FEE)
                .chargeCategory(ChargeCategory.FIXED)
                .fixedAmount(new BigDecimal("100.00"))
                .currencyCode(marketProfile.getDefaultCurrency())
                .isDomestic(true)
                .isCrossBorder(false)
                .effectiveFrom(LocalDate.now())
                .status(ChargeStatus.ACTIVE)
                .build())) created++;

        // Settlement Fee
        if (createIfNotExists("SETTLEMENT_FEE", ChargeConfiguration.builder()
                .chargeCode("SETTLEMENT_FEE")
                .chargeName("Settlement Fee")
                .description("Fee for settlement/payout transactions.")
                .chargeType(ChargeType.SETTLEMENT_FEE)
                .chargeCategory(ChargeCategory.FIXED)
                .fixedAmount(new BigDecimal("15.00"))
                .currencyCode(marketProfile.getDefaultCurrency())
                .isDomestic(true)
                .isCrossBorder(true)
                .effectiveFrom(LocalDate.now())
                .status(ChargeStatus.ACTIVE)
                .build())) created++;

        log.info("Charge configuration initialization complete. Created {} new configurations.", created);
    }

    private boolean createIfNotExists(String chargeCode, ChargeConfiguration config) {
        if (chargeConfigRepository.findByChargeCode(chargeCode).isPresent()) {
            log.debug("Charge configuration '{}' already exists, skipping", chargeCode);
            return false;
        }

        ChargeConfiguration saved = chargeConfigRepository.save(config);
        log.info("Created charge configuration: {} - {} ({}) = {}% / {} fixed",
                saved.getChargeCode(),
                saved.getChargeName(),
                saved.getChargeType(),
                saved.getPercentageRate(),
                saved.getFixedAmount());
        return true;
    }
}
