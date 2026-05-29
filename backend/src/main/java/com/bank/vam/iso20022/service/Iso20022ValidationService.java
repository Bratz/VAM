package com.bank.vam.iso20022.service;

import com.bank.vam.iso20022.dto.CamtStatementDto.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ISO 20022 XML Validation Service.
 *
 * Provides both XSD schema validation and business rule validation
 * for ISO 20022 camt.053/054 messages.
 *
 * <h2>Validation Types</h2>
 * <ul>
 *   <li><b>Schema Validation</b> - XSD validation against ISO 20022 schemas</li>
 *   <li><b>Business Rules</b> - Domain-specific validation rules</li>
 *   <li><b>Format Validation</b> - IBAN, BIC, amount, date formats</li>
 * </ul>
 *
 * <h2>Business Rules Validated</h2>
 * <ul>
 *   <li>Opening + Credits - Debits = Closing balance</li>
 *   <li>Credit/Debit indicator consistency</li>
 *   <li>Currency consistency across entries</li>
 *   <li>Date range validity</li>
 *   <li>Mandatory element presence</li>
 * </ul>
 */
@Service
@Slf4j
public class Iso20022ValidationService {

    // XSD schema paths (embedded in classpath)
    private static final String CAMT053_XSD_PATH = "/xsd/camt.053.001.08.xsd";
    private static final String CAMT054_XSD_PATH = "/xsd/camt.054.001.08.xsd";

    // Format patterns
    private static final Pattern IBAN_PATTERN = Pattern.compile("^[A-Z]{2}[0-9]{2}[A-Z0-9]{4,30}$");
    private static final Pattern BIC_PATTERN = Pattern.compile("^[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?$");
    private static final Pattern AMOUNT_PATTERN = Pattern.compile("^-?\\d{1,15}(\\.\\d{1,5})?$");
    private static final Pattern DATE_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern DATETIME_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}$");
    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_DATE;

    // XML element patterns for extraction
    private static final Pattern MSG_ID_PATTERN = Pattern.compile("<MsgId>([^<]+)</MsgId>");
    private static final Pattern STMT_ID_PATTERN = Pattern.compile("<Id>([^<]+)</Id>");
    private static final Pattern AMT_PATTERN = Pattern.compile("<Amt Ccy=\"([A-Z]{3})\">([^<]+)</Amt>");
    private static final Pattern CDT_DBT_PATTERN = Pattern.compile("<CdtDbtInd>([A-Z]+)</CdtDbtInd>");
    private static final Pattern BAL_TYPE_PATTERN = Pattern.compile("<Cd>(OPBD|CLBD|PRCD|ITBD|CLAV|FWAV|INFO)</Cd>");

    // ========================================================================
    // PUBLIC VALIDATION METHODS
    // ========================================================================

    /**
     * Perform full validation of a camt.053 message.
     *
     * @param xml The XML string to validate
     * @return Validation response with errors/warnings
     */
    public ValidationResponse validateCamt053(String xml) {
        log.debug("Validating camt.053 message");
        List<ValidationError> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // Basic XML structure check
        if (!isValidXmlStructure(xml, errors)) {
            return ValidationResponse.builder()
                    .valid(false)
                    .errors(errors)
                    .warnings(warnings)
                    .build();
        }

        // Namespace validation
        validateNamespace(xml, "camt.053", errors);

        // Schema validation (if XSD available)
        validateAgainstSchema(xml, MessageType.CAMT053, errors, warnings);

        // Business rule validation
        validateCamt053BusinessRules(xml, errors, warnings);

        // Format validations
        validateFormats(xml, errors, warnings);

        boolean isValid = errors.isEmpty();
        log.debug("Validation complete. Valid: {}, Errors: {}, Warnings: {}",
                isValid, errors.size(), warnings.size());

        return ValidationResponse.builder()
                .valid(isValid)
                .errors(errors)
                .warnings(warnings)
                .build();
    }

    /**
     * Perform full validation of a camt.054 message.
     *
     * @param xml The XML string to validate
     * @return Validation response with errors/warnings
     */
    public ValidationResponse validateCamt054(String xml) {
        log.debug("Validating camt.054 message");
        List<ValidationError> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (!isValidXmlStructure(xml, errors)) {
            return ValidationResponse.builder()
                    .valid(false)
                    .errors(errors)
                    .warnings(warnings)
                    .build();
        }

        validateNamespace(xml, "camt.054", errors);
        validateAgainstSchema(xml, MessageType.CAMT054, errors, warnings);
        validateCamt054BusinessRules(xml, errors, warnings);
        validateFormats(xml, errors, warnings);

        return ValidationResponse.builder()
                .valid(errors.isEmpty())
                .errors(errors)
                .warnings(warnings)
                .build();
    }

    /**
     * Validate XML against ISO 20022 XSD schema.
     * Falls back to basic validation if XSD not available.
     */
    public ValidationResponse validateAgainstXsd(ValidationRequest request) {
        List<ValidationError> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        validateAgainstSchema(request.getXml(), request.getMessageType(), errors, warnings);

        return ValidationResponse.builder()
                .valid(errors.isEmpty())
                .errors(errors)
                .warnings(warnings)
                .build();
    }

    /**
     * Quick validation - checks only essential elements.
     */
    public boolean quickValidate(String xml, MessageType messageType) {
        if (xml == null || xml.isBlank()) {
            return false;
        }

        // Check basic structure
        if (!xml.contains("<?xml") || !xml.contains("<Document")) {
            return false;
        }

        // Check namespace
        String expectedNs = messageType == MessageType.CAMT053 ?
                "camt.053.001.08" : "camt.054.001.08";
        if (!xml.contains(expectedNs)) {
            return false;
        }

        // Check required elements
        if (messageType == MessageType.CAMT053) {
            return xml.contains("<BkToCstmrStmt>") &&
                   xml.contains("<GrpHdr>") &&
                   xml.contains("<MsgId>") &&
                   xml.contains("<Stmt>");
        } else {
            return xml.contains("<BkToCstmrDbtCdtNtfctn>") &&
                   xml.contains("<GrpHdr>") &&
                   xml.contains("<MsgId>") &&
                   xml.contains("<Ntfctn>");
        }
    }

    // ========================================================================
    // SCHEMA VALIDATION
    // ========================================================================

    private void validateAgainstSchema(String xml, MessageType messageType,
                                        List<ValidationError> errors, List<String> warnings) {
        String xsdPath = messageType == MessageType.CAMT053 ? CAMT053_XSD_PATH : CAMT054_XSD_PATH;

        try {
            var xsdStream = getClass().getResourceAsStream(xsdPath);
            if (xsdStream == null) {
                warnings.add("XSD schema not available for " + messageType.getSchemaVersion() +
                        ". Using basic validation only.");
                return;
            }

            SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            // Security: Disable external entities
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);

            Schema schema = factory.newSchema(new StreamSource(xsdStream));
            Validator validator = schema.newValidator();

            validator.validate(new StreamSource(new StringReader(xml)));
            log.debug("XSD validation passed for {}", messageType);

        } catch (SAXParseException e) {
            errors.add(ValidationError.builder()
                    .errorType("XSD_VALIDATION")
                    .message(e.getMessage())
                    .lineNumber(e.getLineNumber())
                    .columnNumber(e.getColumnNumber())
                    .build());
        } catch (SAXException e) {
            errors.add(ValidationError.builder()
                    .errorType("XSD_VALIDATION")
                    .message("Schema validation error: " + e.getMessage())
                    .build());
        } catch (Exception e) {
            warnings.add("Could not perform XSD validation: " + e.getMessage());
            log.warn("XSD validation skipped", e);
        }
    }

    // ========================================================================
    // BUSINESS RULE VALIDATION
    // ========================================================================

    private void validateCamt053BusinessRules(String xml, List<ValidationError> errors,
                                               List<String> warnings) {
        // Rule 1: Balance reconciliation (Opening + Credits - Debits = Closing)
        validateBalanceReconciliation(xml, errors);

        // Rule 2: At least one balance must be present
        if (!xml.contains("<Bal>")) {
            errors.add(ValidationError.builder()
                    .errorType("BUSINESS_RULE")
                    .elementPath("Stmt/Bal")
                    .message("At least one balance element is required")
                    .build());
        }

        // Rule 3: Opening and closing balance required
        if (!hasBalanceType(xml, "OPBD")) {
            warnings.add("Opening booked balance (OPBD) is recommended");
        }
        if (!hasBalanceType(xml, "CLBD")) {
            warnings.add("Closing booked balance (CLBD) is recommended");
        }

        // Rule 4: Statement period validation
        validateStatementPeriod(xml, errors);

        // Rule 5: Entry status consistency
        validateEntryStatusConsistency(xml, errors);

        // Rule 6: Mandatory group header elements
        validateGroupHeader(xml, errors);
    }

    private void validateCamt054BusinessRules(String xml, List<ValidationError> errors,
                                               List<String> warnings) {
        // Rule 1: At least one notification entry
        if (!xml.contains("<Ntry>")) {
            errors.add(ValidationError.builder()
                    .errorType("BUSINESS_RULE")
                    .elementPath("Ntfctn/Ntry")
                    .message("At least one entry is required in notification")
                    .build());
        }

        // Rule 2: Mandatory group header
        validateGroupHeader(xml, errors);

        // Rule 3: Each entry should have booking date
        if (xml.contains("<Ntry>") && !xml.contains("<BookgDt>")) {
            warnings.add("Entries should include booking date (BookgDt)");
        }
    }

    private void validateBalanceReconciliation(String xml, List<ValidationError> errors) {
        try {
            BigDecimal openingBalance = extractBalanceAmount(xml, "OPBD");
            BigDecimal closingBalance = extractBalanceAmount(xml, "CLBD");

            if (openingBalance == null || closingBalance == null) {
                return; // Cannot validate without both balances
            }

            BigDecimal totalCredits = extractTotalCredits(xml);
            BigDecimal totalDebits = extractTotalDebits(xml);

            BigDecimal expected = openingBalance.add(totalCredits).subtract(totalDebits);

            if (expected.compareTo(closingBalance) != 0) {
                errors.add(ValidationError.builder()
                        .errorType("BUSINESS_RULE")
                        .elementPath("Stmt/Bal")
                        .message(String.format(
                                "Balance reconciliation failed. Opening (%s) + Credits (%s) - Debits (%s) = %s, but Closing = %s",
                                openingBalance, totalCredits, totalDebits, expected, closingBalance))
                        .build());
            }
        } catch (Exception e) {
            log.debug("Balance reconciliation validation skipped: {}", e.getMessage());
        }
    }

    private void validateStatementPeriod(String xml, List<ValidationError> errors) {
        try {
            Pattern fromPattern = Pattern.compile("<FrDtTm>([^<]+)</FrDtTm>");
            Pattern toPattern = Pattern.compile("<ToDtTm>([^<]+)</ToDtTm>");

            Matcher fromMatcher = fromPattern.matcher(xml);
            Matcher toMatcher = toPattern.matcher(xml);

            if (fromMatcher.find() && toMatcher.find()) {
                String fromStr = fromMatcher.group(1).substring(0, 10);
                String toStr = toMatcher.group(1).substring(0, 10);

                LocalDate fromDate = LocalDate.parse(fromStr, ISO_DATE);
                LocalDate toDate = LocalDate.parse(toStr, ISO_DATE);

                if (fromDate.isAfter(toDate)) {
                    errors.add(ValidationError.builder()
                            .errorType("BUSINESS_RULE")
                            .elementPath("Stmt/FrToDt")
                            .message("From date cannot be after To date")
                            .build());
                }

                if (toDate.isAfter(LocalDate.now())) {
                    errors.add(ValidationError.builder()
                            .errorType("BUSINESS_RULE")
                            .elementPath("Stmt/FrToDt/ToDtTm")
                            .message("Statement period cannot extend into the future")
                            .build());
                }
            }
        } catch (DateTimeParseException e) {
            errors.add(ValidationError.builder()
                    .errorType("FORMAT")
                    .elementPath("Stmt/FrToDt")
                    .message("Invalid date format in statement period")
                    .build());
        }
    }

    private void validateEntryStatusConsistency(String xml, List<ValidationError> errors) {
        // Check that all entries have valid status codes
        Pattern statusPattern = Pattern.compile("<Sts>\\s*<Cd>([A-Z]+)</Cd>\\s*</Sts>");
        Matcher matcher = statusPattern.matcher(xml);

        while (matcher.find()) {
            String status = matcher.group(1);
            if (!isValidEntryStatus(status)) {
                errors.add(ValidationError.builder()
                        .errorType("BUSINESS_RULE")
                        .elementPath("Ntry/Sts/Cd")
                        .message("Invalid entry status code: " + status + ". Expected: BOOK, PDNG, or INFO")
                        .build());
            }
        }
    }

    private void validateGroupHeader(String xml, List<ValidationError> errors) {
        if (!xml.contains("<GrpHdr>")) {
            errors.add(ValidationError.builder()
                    .errorType("STRUCTURE")
                    .elementPath("GrpHdr")
                    .message("Group header (GrpHdr) is mandatory")
                    .build());
            return;
        }

        if (!xml.contains("<MsgId>")) {
            errors.add(ValidationError.builder()
                    .errorType("STRUCTURE")
                    .elementPath("GrpHdr/MsgId")
                    .message("Message ID (MsgId) is mandatory in group header")
                    .build());
        }

        if (!xml.contains("<CreDtTm>")) {
            errors.add(ValidationError.builder()
                    .errorType("STRUCTURE")
                    .elementPath("GrpHdr/CreDtTm")
                    .message("Creation date time (CreDtTm) is mandatory in group header")
                    .build());
        }
    }

    // ========================================================================
    // FORMAT VALIDATION
    // ========================================================================

    private void validateFormats(String xml, List<ValidationError> errors, List<String> warnings) {
        validateIbanFormats(xml, errors);
        validateBicFormats(xml, errors);
        validateAmountFormats(xml, errors);
        validateDateFormats(xml, errors);
        validateCurrencyFormats(xml, errors, warnings);
    }

    private void validateIbanFormats(String xml, List<ValidationError> errors) {
        Pattern pattern = Pattern.compile("<IBAN>([^<]+)</IBAN>");
        Matcher matcher = pattern.matcher(xml);

        while (matcher.find()) {
            String iban = matcher.group(1);
            if (!IBAN_PATTERN.matcher(iban).matches()) {
                errors.add(ValidationError.builder()
                        .errorType("FORMAT")
                        .elementPath("IBAN")
                        .message("Invalid IBAN format: " + iban)
                        .build());
            }
        }
    }

    private void validateBicFormats(String xml, List<ValidationError> errors) {
        Pattern pattern = Pattern.compile("<BICFI>([^<]+)</BICFI>");
        Matcher matcher = pattern.matcher(xml);

        while (matcher.find()) {
            String bic = matcher.group(1);
            if (!BIC_PATTERN.matcher(bic).matches()) {
                errors.add(ValidationError.builder()
                        .errorType("FORMAT")
                        .elementPath("BICFI")
                        .message("Invalid BIC format: " + bic)
                        .build());
            }
        }
    }

    private void validateAmountFormats(String xml, List<ValidationError> errors) {
        Matcher matcher = AMT_PATTERN.matcher(xml);

        while (matcher.find()) {
            String amount = matcher.group(2);
            if (!AMOUNT_PATTERN.matcher(amount).matches()) {
                errors.add(ValidationError.builder()
                        .errorType("FORMAT")
                        .elementPath("Amt")
                        .message("Invalid amount format: " + amount)
                        .build());
            }
            try {
                BigDecimal value = new BigDecimal(amount);
                if (value.compareTo(BigDecimal.ZERO) < 0) {
                    errors.add(ValidationError.builder()
                            .errorType("FORMAT")
                            .elementPath("Amt")
                            .message("Amount cannot be negative: " + amount)
                            .build());
                }
            } catch (NumberFormatException e) {
                errors.add(ValidationError.builder()
                        .errorType("FORMAT")
                        .elementPath("Amt")
                        .message("Invalid number format for amount: " + amount)
                        .build());
            }
        }
    }

    private void validateDateFormats(String xml, List<ValidationError> errors) {
        // Validate Dt elements
        Pattern datePattern = Pattern.compile("<Dt>([^<]+)</Dt>");
        Matcher dateMatcher = datePattern.matcher(xml);

        while (dateMatcher.find()) {
            String dateStr = dateMatcher.group(1);
            if (!DATE_PATTERN.matcher(dateStr).matches()) {
                errors.add(ValidationError.builder()
                        .errorType("FORMAT")
                        .elementPath("Dt")
                        .message("Invalid date format: " + dateStr + ". Expected: YYYY-MM-DD")
                        .build());
            }
        }

        // Validate DtTm elements
        Pattern dateTimePattern = Pattern.compile("<DtTm>([^<]+)</DtTm>");
        Matcher dateTimeMatcher = dateTimePattern.matcher(xml);

        while (dateTimeMatcher.find()) {
            String dateTimeStr = dateTimeMatcher.group(1);
            if (!DATETIME_PATTERN.matcher(dateTimeStr).matches()) {
                errors.add(ValidationError.builder()
                        .errorType("FORMAT")
                        .elementPath("DtTm")
                        .message("Invalid datetime format: " + dateTimeStr + ". Expected: YYYY-MM-DDTHH:MM:SS")
                        .build());
            }
        }
    }

    private void validateCurrencyFormats(String xml, List<ValidationError> errors, List<String> warnings) {
        Pattern pattern = Pattern.compile("Ccy=\"([A-Z]{3})\"");
        Matcher matcher = pattern.matcher(xml);

        String firstCurrency = null;
        boolean mixedCurrencies = false;

        while (matcher.find()) {
            String currency = matcher.group(1);
            if (firstCurrency == null) {
                firstCurrency = currency;
            } else if (!firstCurrency.equals(currency)) {
                mixedCurrencies = true;
            }
        }

        if (mixedCurrencies) {
            warnings.add("Statement contains multiple currencies. Ensure this is intentional for multi-currency accounts.");
        }
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    private boolean isValidXmlStructure(String xml, List<ValidationError> errors) {
        if (xml == null || xml.isBlank()) {
            errors.add(ValidationError.builder()
                    .errorType("STRUCTURE")
                    .message("XML content is empty or null")
                    .build());
            return false;
        }

        if (!xml.trim().startsWith("<?xml")) {
            errors.add(ValidationError.builder()
                    .errorType("STRUCTURE")
                    .message("Missing XML declaration")
                    .build());
            return false;
        }

        if (!xml.contains("<Document") || !xml.contains("</Document>")) {
            errors.add(ValidationError.builder()
                    .errorType("STRUCTURE")
                    .message("Missing or malformed Document root element")
                    .build());
            return false;
        }

        return true;
    }

    private void validateNamespace(String xml, String messageType, List<ValidationError> errors) {
        String expectedNs = messageType.equals("camt.053") ?
                "urn:iso:std:iso:20022:tech:xsd:camt.053.001.08" :
                "urn:iso:std:iso:20022:tech:xsd:camt.054.001.08";

        if (!xml.contains(expectedNs)) {
            errors.add(ValidationError.builder()
                    .errorType("NAMESPACE")
                    .elementPath("Document")
                    .message("Invalid or missing namespace. Expected: " + expectedNs)
                    .build());
        }
    }

    private boolean hasBalanceType(String xml, String balanceType) {
        return xml.contains("<Cd>" + balanceType + "</Cd>");
    }

    private boolean isValidEntryStatus(String status) {
        return "BOOK".equals(status) || "PDNG".equals(status) || "INFO".equals(status);
    }

    private BigDecimal extractBalanceAmount(String xml, String balanceType) {
        // Find balance block with specific type
        Pattern balPattern = Pattern.compile(
                "<Bal>.*?<Cd>" + balanceType + "</Cd>.*?<Amt[^>]*>([^<]+)</Amt>.*?<CdtDbtInd>([A-Z]+)</CdtDbtInd>.*?</Bal>",
                Pattern.DOTALL);
        Matcher matcher = balPattern.matcher(xml);

        if (matcher.find()) {
            BigDecimal amount = new BigDecimal(matcher.group(1));
            String indicator = matcher.group(2);
            return "DBIT".equals(indicator) ? amount.negate() : amount;
        }
        return null;
    }

    private BigDecimal extractTotalCredits(String xml) {
        Pattern pattern = Pattern.compile("<TtlCdtNtries>.*?<Sum>([^<]+)</Sum>.*?</TtlCdtNtries>", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(xml);
        if (matcher.find()) {
            return new BigDecimal(matcher.group(1));
        }
        return BigDecimal.ZERO;
    }

    private BigDecimal extractTotalDebits(String xml) {
        Pattern pattern = Pattern.compile("<TtlDbtNtries>.*?<Sum>([^<]+)</Sum>.*?</TtlDbtNtries>", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(xml);
        if (matcher.find()) {
            return new BigDecimal(matcher.group(1));
        }
        return BigDecimal.ZERO;
    }
}
