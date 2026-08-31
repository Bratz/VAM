package com.bank.vam.iso20022.dto;

import com.bank.vam.iso20022.dto.Camt053Dto.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * ISO 20022 camt.054 (Bank to Customer Debit Credit Notification) DTOs.
 *
 * <p>camt.054 is used for real-time or near-real-time notifications of individual
 * debit and credit entries on an account. Unlike camt.053 (end-of-day statement),
 * camt.054 notifications are sent as transactions occur.</p>
 *
 * <h2>Key Differences from camt.053:</h2>
 * <ul>
 *   <li>Real-time vs End-of-day: camt.054 is immediate, camt.053 is periodic</li>
 *   <li>Notification uses &lt;Ntfctn&gt; element, Statement uses &lt;Stmt&gt;</li>
 *   <li>No balances required in camt.054 (optional)</li>
 *   <li>Typically single or few entries per message</li>
 * </ul>
 *
 * <h2>Message Structure:</h2>
 * <pre>
 * Document (camt.054.001.08)
 *   └── BkToCstmrDbtCdtNtfctn (Bank to Customer Debit Credit Notification)
 *         ├── GrpHdr (Group Header)
 *         │     ├── MsgId (Message Identification)
 *         │     ├── CreDtTm (Creation Date Time)
 *         │     └── MsgPgntn (Message Pagination - optional)
 *         └── Ntfctn[] (Notification - 1..n)
 *               ├── Id (Notification Identification)
 *               ├── NtfctnPgntn (Notification Pagination)
 *               ├── ElctrncSeqNb (Electronic Sequence Number)
 *               ├── RptgSeq (Reporting Sequence)
 *               ├── LglSeqNb (Legal Sequence Number)
 *               ├── CreDtTm (Creation Date Time)
 *               ├── FrToDt (From To Date - optional)
 *               ├── Acct (Account)
 *               ├── RltdAcct (Related Account - optional)
 *               ├── TxsSummry (Transaction Summary - optional)
 *               └── Ntry[] (Entry - 0..n)
 *                     ├── NtryRef (Entry Reference)
 *                     ├── Amt (Amount)
 *                     ├── CdtDbtInd (Credit/Debit)
 *                     ├── Sts (Status)
 *                     ├── BookgDt (Booking Date)
 *                     ├── ValDt (Value Date)
 *                     └── NtryDtls[] (Entry Details)
 * </pre>
 *
 * <h2>Use Cases in VAM:</h2>
 * <ul>
 *   <li>Real-time collection notifications to corporates</li>
 *   <li>Payment status notifications</li>
 *   <li>Sweep/pool movement notifications</li>
 *   <li>Fee posting notifications</li>
 *   <li>Interest posting notifications</li>
 *   <li>POBO/ROBO transaction notifications</li>
 * </ul>
 *
 * @see Camt053Dto for shared types (Entry, Balance, Party, etc.)
 * @see <a href="https://www.iso20022.org/catalogue-messages/camt-cash-management">ISO 20022 camt Messages</a>
 * @version camt.054.001.08
 */
public class Camt054Dto {

    // ========================================================================
    // ENUMS - Notification Specific
    // ========================================================================

    /**
     * Notification Type - VAM-specific categorization.
     */
    public enum NotificationType {
        /** Credit notification - money received */
        CREDIT("CREDIT", "Credit Notification"),

        /** Debit notification - money sent */
        DEBIT("DEBIT", "Debit Notification"),

        /** Collection received */
        COLLECTION("COLLECTION", "Collection Received"),

        /** Payment executed */
        PAYMENT("PAYMENT", "Payment Executed"),

        /** Internal transfer */
        TRANSFER("TRANSFER", "Internal Transfer"),

        /** Sweep movement */
        SWEEP("SWEEP", "Sweep Movement"),

        /** Pool operation */
        POOL("POOL", "Pool Operation"),

        /** Fee posting */
        FEE("FEE", "Fee Posting"),

        /** Interest posting */
        INTEREST("INTEREST", "Interest Posting"),

        /** POBO transaction */
        POBO("POBO", "Pay-On-Behalf-Of"),

        /** ROBO transaction */
        ROBO("ROBO", "Receive-On-Behalf-Of"),

        /** Reversal */
        REVERSAL("REVERSAL", "Reversal"),

        /** Exception handling */
        EXCEPTION("EXCEPTION", "Exception Transaction");

        private final String code;
        private final String description;

        NotificationType(String code, String description) {
            this.code = code;
            this.description = description;
        }

        public String getCode() { return code; }
        public String getDescription() { return description; }
    }

    /**
     * Notification Priority.
     */
    public enum NotificationPriority {
        /** Normal priority - batch processing acceptable */
        NORMAL("NORM", "Normal"),

        /** High priority - expedited processing */
        HIGH("HIGH", "High"),

        /** Urgent - immediate processing */
        URGENT("URGN", "Urgent"),

        /** Real-time - synchronous processing */
        REALTIME("RTIM", "Real-Time");

        private final String code;
        private final String description;

        NotificationPriority(String code, String description) {
            this.code = code;
            this.description = description;
        }

        public String getCode() { return code; }
        public String getDescription() { return description; }
    }

    /**
     * Notification Delivery Channel.
     */
    public enum DeliveryChannel {
        /** API push notification */
        API("API", "REST API Push"),

        /** Webhook callback */
        WEBHOOK("WEBHOOK", "Webhook Callback"),

        /** Message queue */
        QUEUE("QUEUE", "Message Queue"),

        /** Email notification */
        EMAIL("EMAIL", "Email"),

        /** SMS notification */
        SMS("SMS", "SMS"),

        /** SWIFT network */
        SWIFT("SWIFT", "SWIFT Network"),

        /** File transfer */
        FILE("FILE", "File Transfer");

        private final String code;
        private final String description;

        DeliveryChannel(String code, String description) {
            this.code = code;
            this.description = description;
        }

        public String getCode() { return code; }
        public String getDescription() { return description; }
    }

    // ========================================================================
    // REQUEST DTOs
    // ========================================================================

    /**
     * Request to subscribe to notifications for an account.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NotificationSubscriptionRequest {

        /** Virtual Account ID to subscribe */
        @NotNull(message = "Virtual Account ID is required")
        private UUID virtualAccountId;

        /** Include child VA notifications (for parent VAs) */
        @Builder.Default
        private Boolean includeChildVas = false;

        /** Types of notifications to receive */
        private List<NotificationType> notificationTypes;

        /** Minimum amount for notification (optional threshold) */
        private BigDecimal minimumAmount;

        /** Delivery channel */
        @NotNull
        private DeliveryChannel deliveryChannel;

        /** Webhook URL (if channel is WEBHOOK) */
        private String webhookUrl;

        /** Email address (if channel is EMAIL) */
        private String emailAddress;

        /** Phone number (if channel is SMS) */
        private String phoneNumber;

        /** Message queue name (if channel is QUEUE) */
        private String queueName;

        /** Priority level */
        @Builder.Default
        private NotificationPriority priority = NotificationPriority.NORMAL;

        /** Active from date */
        private LocalDate activeFrom;

        /** Active until date */
        private LocalDate activeUntil;

        /** External reference for client tracking */
        @Size(max = 35)
        private String externalReference;
    }

    /**
     * Response for subscription creation.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NotificationSubscriptionResponse {
        private UUID subscriptionId;
        private UUID virtualAccountId;
        private String vaNumber;
        private List<NotificationType> notificationTypes;
        private DeliveryChannel deliveryChannel;
        private String status;
        private LocalDateTime createdAt;
        private LocalDateTime expiresAt;
        private String webhookSecret;  // For webhook authentication
    }

    /**
     * Request to generate a manual notification (admin/testing).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GenerateNotificationRequest {

        /** Transaction ID to notify about */
        private UUID transactionId;

        /** Alternatively, specify details manually */
        private UUID virtualAccountId;
        private NotificationType notificationType;
        private BigDecimal amount;
        private String currencyCode;
        private CreditDebitIndicator creditDebitIndicator;
        private String description;
        private String counterpartyName;

        /** Force generation even if already notified */
        @Builder.Default
        private Boolean forceGenerate = false;

        /** Specific delivery channel (overrides subscription) */
        private DeliveryChannel deliveryChannel;
    }

    // ========================================================================
    // RESPONSE DTOs - Document Level
    // ========================================================================

    /**
     * Root document for camt.054 notification.
     * Maps to: Document/BkToCstmrDbtCdtNtfctn
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Camt054Document {

        /** Message format version */
        @Builder.Default
        private String messageDefinitionIdentifier = "camt.054.001.08";

        /** XML namespace */
        @Builder.Default
        private String namespace = "urn:iso:std:iso:20022:tech:xsd:camt.054.001.08";

        /** Group Header - Required */
        @NotNull
        private GroupHeader groupHeader;

        /** Notification(s) - One or more notifications */
        @NotNull
        private List<Notification> notifications;

        /** Raw XML content (populated when format=XML) */
        private String xmlContent;

        /** Processing/delivery status */
        private DeliveryStatus deliveryStatus;
    }

    /**
     * Delivery Status for notification.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DeliveryStatus {
        /** Overall success */
        private boolean success;

        /** Status code */
        private String statusCode;

        /** Status message */
        private String statusMessage;

        /** Delivery channel used */
        private DeliveryChannel channel;

        /** Delivery timestamp */
        private LocalDateTime deliveredAt;

        /** Retry count if failed */
        private Integer retryCount;

        /** Next retry timestamp */
        private LocalDateTime nextRetryAt;

        /** Webhook response status (if applicable) */
        private Integer webhookResponseStatus;

        /** Processing time in ms */
        private Long processingTimeMs;
    }

    // ========================================================================
    // NOTIFICATION (Ntfctn)
    // ========================================================================

    /**
     * Account Notification.
     * Maps to: BkToCstmrDbtCdtNtfctn/Ntfctn
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Notification {

        /**
         * Notification Identification - Unique ID for this notification.
         * Maps to: Ntfctn/Id
         */
        @NotBlank(message = "Notification ID is required")
        @Size(max = 35, message = "Notification ID must not exceed 35 characters")
        private String notificationId;

        /**
         * Notification Pagination - For multi-page notifications.
         * Maps to: Ntfctn/NtfctnPgntn
         */
        private Pagination notificationPagination;

        /**
         * Electronic Sequence Number - Sequential number.
         * Maps to: Ntfctn/ElctrncSeqNb
         */
        private Long electronicSequenceNumber;

        /**
         * Reporting Sequence - For ordered delivery.
         * Maps to: Ntfctn/RptgSeq
         */
        private ReportingSequence reportingSequence;

        /**
         * Legal Sequence Number - Regulatory sequence number.
         * Maps to: Ntfctn/LglSeqNb
         */
        private Long legalSequenceNumber;

        /**
         * Creation Date Time - When notification was created.
         * Maps to: Ntfctn/CreDtTm
         */
        @NotNull
        private LocalDateTime creationDateTime;

        /**
         * From To Date - Optional date range for the entries.
         * Maps to: Ntfctn/FrToDt
         */
        private DatePeriod fromToDate;

        /**
         * Copy Duplicate Indicator - Is this a copy?
         * Maps to: Ntfctn/CpyDplctInd
         */
        private String copyDuplicateIndicator;

        /**
         * Reporting Source - Source of reporting data.
         * Maps to: Ntfctn/RptgSrc
         */
        private String reportingSource;

        /**
         * Account - The account this notification is for.
         * Maps to: Ntfctn/Acct
         */
        @NotNull
        private AccountInfo account;

        /**
         * Related Account - For notifications referencing another account.
         * Maps to: Ntfctn/RltdAcct
         */
        private AccountInfo relatedAccount;

        /**
         * Interest - Interest information (optional).
         * Maps to: Ntfctn/Intrst
         */
        private List<InterestInfo> interest;

        /**
         * Transactions Summary - Aggregated totals (optional in camt.054).
         * Maps to: Ntfctn/TxsSummry
         */
        private TransactionsSummary transactionsSummary;

        /**
         * Entry - Individual transaction entries.
         * Maps to: Ntfctn/Ntry
         */
        @NotNull
        private List<Entry> entries;

        /**
         * Additional Notification Information.
         * Maps to: Ntfctn/AddtlNtfctnInf
         */
        private String additionalNotificationInformation;

        // ====================================================================
        // VAM-Specific Extensions
        // ====================================================================

        /** Notification type for VAM */
        private NotificationType notificationType;

        /** Notification priority */
        private NotificationPriority priority;

        /** Program information */
        private UUID programId;
        private String programCode;
        private String programName;

        /** Corporate information */
        private UUID corporateId;
        private String corporateName;

        /** Legal entity information */
        private UUID legalEntityId;
        private String legalEntityCode;

        /** Subscription that triggered this notification */
        private UUID subscriptionId;

        /** Correlation ID for tracking */
        private String correlationId;

        /** Is this a real-time notification? */
        @Builder.Default
        private Boolean isRealTime = true;

        /** Current balance after entries (convenience field) */
        private BigDecimal balanceAfter;

        /** Available balance after entries */
        private BigDecimal availableBalanceAfter;
    }

    /**
     * Reporting Sequence for ordered delivery.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReportingSequence {
        /** Sequence range start */
        private Long fromSequence;

        /** Sequence range end */
        private Long toSequence;

        /** From to indicator */
        private String fromToIndicator;
    }

    // ========================================================================
    // NOTIFICATION ENTRY - Extended for Real-Time Context
    // ========================================================================

    /**
     * Extended Entry for camt.054 with real-time specific fields.
     * Extends the base Entry from camt.053 with notification-specific data.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NotificationEntry {

        // === Standard Entry Fields (from camt.053) ===
        private String entryReference;
        private AmountWithCurrency amount;
        private CreditDebitIndicator creditDebitIndicator;
        private Boolean reversalIndicator;
        private EntryStatus status;
        private DateInfo bookingDate;
        private DateInfo valueDate;
        private String accountServicerReference;
        private List<CashAvailability> availability;
        private BankTransactionCode bankTransactionCode;
        private Boolean commissionWaiverIndicator;
        private String additionalInformationIndicator;
        private AmountDetails amountDetails;
        private Charges charges;
        private String technicalInputChannel;
        private List<InterestInfo> interest;
        private CardTransaction cardTransaction;
        private List<EntryDetails> entryDetails;
        private String additionalEntryInformation;

        // === VAM Extensions (from camt.053 Entry) ===
        private UUID transactionId;
        private UUID sourceVaId;
        private String sourceVaNumber;
        private String owningEntityCode;
        private String movementType;
        private String transactionCategory;
        private Boolean isPobo;
        private String behalfOfEntity;
        private BigDecimal balanceBefore;
        private BigDecimal balanceAfter;

        // === Real-Time Notification Specific ===

        /** Notification type */
        private NotificationType notificationType;

        /** Transaction timestamp (exact time) */
        private LocalDateTime transactionTimestamp;

        /** Processing latency in milliseconds */
        private Long processingLatencyMs;

        /** Channel through which transaction was received/sent */
        private String transactionChannel;

        /** Original payment reference (for payments) */
        private String originalPaymentReference;

        /** VIBAN used for routing (for collections) */
        private String viban;

        /** Matched invoice reference (for collections) */
        private String matchedInvoiceReference;

        /** Match status (AUTO, MANUAL, PARTIAL, UNMATCHED) */
        private String matchStatus;

        /** Remitter information (for credits) */
        private RemitterInfo remitterInfo;

        /** Beneficiary information (for debits) */
        private BeneficiaryInfo beneficiaryInfo;

        /** POBO/ROBO specific: on behalf of entity */
        private OnBehalfOfInfo onBehalfOf;

        /** Related transaction IDs (for multi-leg transactions) */
        private List<UUID> relatedTransactionIds;

        /** Sweep/Pool reference (if applicable) */
        private String sweepPoolReference;

        /** Fee breakdown (if fees applied) */
        private List<FeeItem> fees;

        /** Is this a real-time transaction? */
        @Builder.Default
        private Boolean isRealTime = true;

        /** Requires action flag */
        @Builder.Default
        private Boolean requiresAction = false;

        /** Action type if action required */
        private String actionType;
    }

    /**
     * Remitter (Sender) Information for credit notifications.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RemitterInfo {
        private String name;
        private String accountNumber;
        private String iban;
        private String bic;
        private String bankName;
        private String country;
        private String reference;
    }

    /**
     * Beneficiary (Receiver) Information for debit notifications.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BeneficiaryInfo {
        private String name;
        private String accountNumber;
        private String iban;
        private String bic;
        private String bankName;
        private String country;
        private String reference;
    }

    /**
     * On Behalf Of Information for POBO/ROBO transactions.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OnBehalfOfInfo {
        /** Entity ID on whose behalf */
        private UUID entityId;

        /** Entity code */
        private String entityCode;

        /** Entity name */
        private String entityName;

        /** VA ID on whose behalf */
        private UUID vaId;

        /** VA number */
        private String vaNumber;

        /** Type: POBO or ROBO */
        private String type;
    }

    /**
     * Fee Item for fee breakdown.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FeeItem {
        private String feeType;
        private String feeCode;
        private String feeName;
        private BigDecimal amount;
        private String currency;
        private Boolean waived;
        private String waiverReason;
    }

    // ========================================================================
    // WEBHOOK/CALLBACK DTOs
    // ========================================================================

    /**
     * Webhook Notification Payload - Sent to client webhooks.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WebhookNotificationPayload {

        /** Unique notification ID */
        private String notificationId;

        /** Timestamp of notification */
        private LocalDateTime timestamp;

        /** Event type */
        private NotificationType eventType;

        /** Account information */
        private WebhookAccountInfo account;

        /** Transaction details */
        private WebhookTransactionInfo transaction;

        /** Balance after transaction */
        private WebhookBalanceInfo balance;

        /** HMAC signature for verification */
        private String signature;

        /** Signature algorithm */
        @Builder.Default
        private String signatureAlgorithm = "HMAC-SHA256";

        /** Delivery attempt number */
        private Integer deliveryAttempt;

        /** Full camt.054 XML (optional) */
        private String camt054Xml;
    }

    /**
     * Webhook Account Info - Simplified account information.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WebhookAccountInfo {
        private UUID virtualAccountId;
        private String vaNumber;
        private String vaName;
        private String viban;
        private String currency;
        private UUID corporateId;
        private String corporateName;
        private String owningEntityCode;
    }

    /**
     * Webhook Transaction Info - Simplified transaction details.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WebhookTransactionInfo {
        private UUID transactionId;
        private String referenceNumber;
        private String type;  // CREDIT, DEBIT
        private BigDecimal amount;
        private String currency;
        private LocalDateTime transactionDate;
        private LocalDate valueDate;
        private String description;
        private String counterpartyName;
        private String counterpartyAccount;
        private String channel;
        private String status;
        private Boolean isPobo;
        private Boolean isRobo;
        private String behalfOfEntity;
        private String externalReference;
        private String correlationId;
    }

    /**
     * Webhook Balance Info - Balance after transaction.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WebhookBalanceInfo {
        private BigDecimal currentBalance;
        private BigDecimal availableBalance;
        private BigDecimal heldBalance;
        private String currency;
        private LocalDateTime asOf;
    }

    /**
     * Webhook Delivery Result - Returned after webhook attempt.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WebhookDeliveryResult {
        private String notificationId;
        private String webhookUrl;
        private boolean success;
        private Integer httpStatusCode;
        private String responseBody;
        private Long responseTimeMs;
        private Integer attemptNumber;
        private LocalDateTime deliveredAt;
        private String errorMessage;
        private Boolean willRetry;
        private LocalDateTime nextRetryAt;
    }

    // ========================================================================
    // NOTIFICATION HISTORY/QUERY DTOs
    // ========================================================================

    /**
     * Request to query notification history.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NotificationHistoryRequest {
        private UUID virtualAccountId;
        private UUID subscriptionId;
        private List<NotificationType> notificationTypes;
        private LocalDateTime fromDateTime;
        private LocalDateTime toDateTime;
        private CreditDebitIndicator creditDebitIndicator;
        private BigDecimal minAmount;
        private BigDecimal maxAmount;
        private String status;
        private DeliveryChannel deliveryChannel;
        private Boolean onlyFailed;

        @Builder.Default
        private Integer page = 0;

        @Builder.Default
        private Integer pageSize = 50;

        @Builder.Default
        private String sortBy = "creationDateTime";

        @Builder.Default
        private String sortOrder = "desc";
    }

    /**
     * Response for notification history query.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NotificationHistoryResponse {
        private List<NotificationHistoryItem> notifications;
        private Integer page;
        private Integer pageSize;
        private Long totalElements;
        private Integer totalPages;
        private NotificationStats stats;
    }

    /**
     * Individual notification history item.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NotificationHistoryItem {
        private String notificationId;
        private UUID virtualAccountId;
        private String vaNumber;
        private NotificationType notificationType;
        private CreditDebitIndicator creditDebitIndicator;
        private BigDecimal amount;
        private String currency;
        private LocalDateTime creationDateTime;
        private DeliveryChannel deliveryChannel;
        private String deliveryStatus;
        private LocalDateTime deliveredAt;
        private Integer deliveryAttempts;
        private String counterpartyName;
        private UUID transactionId;
        private String transactionReference;
        private String correlationId;
    }

    /**
     * Notification statistics.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NotificationStats {
        private Long totalNotifications;
        private Long successfulDeliveries;
        private Long failedDeliveries;
        private Long pendingDeliveries;
        private Long creditNotifications;
        private Long debitNotifications;
        private BigDecimal totalCreditAmount;
        private BigDecimal totalDebitAmount;
        private Double averageDeliveryTimeMs;
    }

    // ========================================================================
    // BATCH NOTIFICATION DTOs
    // ========================================================================

    /**
     * Request to generate batch notifications (e.g., end-of-day summary).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BatchNotificationRequest {
        private UUID virtualAccountId;
        private LocalDate forDate;
        private Boolean includeChildVas;
        private List<NotificationType> notificationTypes;
        private Boolean consolidateEntries;
        private DeliveryChannel deliveryChannel;
    }

    /**
     * Response for batch notification generation.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BatchNotificationResponse {
        private String batchId;
        private UUID virtualAccountId;
        private LocalDate forDate;
        private Integer notificationCount;
        private Integer entryCount;
        private BigDecimal totalCredits;
        private BigDecimal totalDebits;
        private String status;
        private LocalDateTime generatedAt;
        private List<String> notificationIds;
    }

    // ========================================================================
    // RETRY/RESEND DTOs
    // ========================================================================

    /**
     * Request to retry failed notification delivery.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RetryNotificationRequest {
        private String notificationId;
        private DeliveryChannel overrideChannel;
        private String overrideWebhookUrl;
        private String overrideEmailAddress;
        private Boolean forceDelivery;
    }

    /**
     * Response for retry request.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RetryNotificationResponse {
        private String notificationId;
        private Boolean retryScheduled;
        private LocalDateTime scheduledAt;
        private DeliveryChannel channel;
        private Integer previousAttempts;
        private String message;
    }
}
