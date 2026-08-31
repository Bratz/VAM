package com.bank.vam.entity.hierarchy;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Configuration for each hierarchy level per program.
 * Defines what each level represents for a specific program.
 *
 * CONFIGURABLE DEPTH (v5.3.0):
 * ============================
 * Hierarchy depth is now configurable per program (default: 20, max: 50).
 * No longer restricted to exactly 7 levels.
 *
 * Example configurations (7-level IHB):
 * - Level 1: CURRENCY (INR, USD, AED) - Always ROOT/MASTER
 * - Level 2: REGION (NORTH, SOUTH, EAST, WEST)
 * - Level 3: COUNTRY/STATE (MH, KA, TN)
 * - Level 4: CITY/ENTITY (MUMBAI, PUNE)
 * - Level 5: DEPARTMENT/CUSTOMER (SALES, ENGINEERING)
 * - Level 6: ACCOUNT_TYPE (PAYABLES, RECEIVABLES)
 * - Level 7: VIRTUAL_ACCOUNT (VA-001, VA-002) - Leaf level
 *
 * Deeper hierarchies (e.g., holding company with 12 levels):
 * - Level 1: CURRENCY
 * - Level 2: HOLDING_COMPANY
 * - Level 3: SUBSIDIARY_GROUP
 * - Level 4: SUBSIDIARY
 * - Level 5: REGION
 * - Level 6: COUNTRY
 * - Level 7: ENTITY
 * - Level 8: DEPARTMENT
 * - Level 9: COST_CENTER
 * - Level 10: PROJECT
 * - Level 11: ACCOUNT_TYPE
 * - Level 12: VIRTUAL_ACCOUNT
 */
@Entity
@Table(name = "hierarchy_level_configs",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_program_level",
        columnNames = {"program_id", "level_number"}
    ),
    indexes = {
        @Index(name = "idx_level_configs_program_id", columnList = "program_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HierarchyLevelConfig extends BaseEntity {

    /**
     * Reference to the program this configuration belongs to.
     */
    @Column(name = "program_id", nullable = false)
    private UUID programId;

    /**
     * Level number (1 to maxDepth, configurable per program).
     * Level 1 is always the root (MASTER/CURRENCY).
     * The configured maxDepth level is typically VIRTUAL_ACCOUNT (leaf).
     */
    @Column(name = "level_number", nullable = false)
    private Integer levelNumber;

    /**
     * Maximum depth for this program's hierarchy.
     * Used for validation. Default: 20, Max: 50.
     */
    @Column(name = "max_depth")
    @Builder.Default
    private Integer maxDepth = HierarchyNode.DEFAULT_MAX_DEPTH;

    /**
     * Display name for this level.
     * Examples: "Currency", "Region", "State", "Entity", "Department", "Account Type", "Virtual Account"
     */
    @Column(name = "level_name", nullable = false, length = 50)
    private String levelName;

    /**
     * Type of dimension this level represents.
     * Used for validation and UI rendering.
     */
    @Column(name = "dimension_type", nullable = false, length = 50)
    private String dimensionType;

    /**
     * Whether this level is required in the hierarchy.
     * If false, nodes at this level can be skipped.
     */
    @Column(name = "is_required")
    @Builder.Default
    private Boolean isRequired = true;

    /**
     * JSON array of allowed values for this level.
     * Example: ["NORTH", "SOUTH", "EAST", "WEST"]
     * Null means any value is allowed.
     * 
     * Uses Hibernate 6 @JdbcTypeCode for proper JSONB mapping.
     */
    @Column(name = "allowed_values", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String allowedValues;

    /**
     * Description of what this level represents.
     */
    @Column(name = "description", length = 255)
    private String description;

    /**
     * Display order for UI rendering.
     */
    @Column(name = "display_order")
    private Integer displayOrder;

    /**
     * Icon identifier for UI rendering.
     */
    @Column(name = "icon", length = 50)
    private String icon;

    // ========================================================================
    // Common dimension type constants
    // ========================================================================
    
    public static final String DIM_CURRENCY = "CURRENCY";
    public static final String DIM_REGION = "REGION";
    public static final String DIM_COUNTRY = "COUNTRY";
    public static final String DIM_STATE = "STATE";
    public static final String DIM_CITY = "CITY";
    public static final String DIM_ENTITY = "ENTITY";
    public static final String DIM_DEPARTMENT = "DEPARTMENT";
    public static final String DIM_COST_CENTER = "COST_CENTER";
    public static final String DIM_ACCOUNT_TYPE = "ACCOUNT_TYPE";
    public static final String DIM_VIRTUAL_ACCOUNT = "VIRTUAL_ACCOUNT";
    public static final String DIM_CHANNEL = "CHANNEL";
    public static final String DIM_PLATFORM = "PLATFORM";
    public static final String DIM_SEGMENT = "SEGMENT";
    public static final String DIM_CUSTOMER = "CUSTOMER";
    public static final String DIM_MERCHANT = "MERCHANT";
    public static final String DIM_PARTNER = "PARTNER";
    public static final String DIM_TIER = "TIER";
    public static final String DIM_BATCH = "BATCH";
    public static final String DIM_DENOMINATION = "DENOMINATION";
    public static final String DIM_CARD_TYPE = "CARD_TYPE";
    public static final String DIM_CARD_FORMAT = "CARD_FORMAT";
    public static final String DIM_NETWORK = "NETWORK";
    public static final String DIM_KYC_TIER = "KYC_TIER";
    public static final String DIM_BUDGET_OWNER = "BUDGET_OWNER";
    public static final String DIM_POINTS_STATUS = "POINTS_STATUS";
    public static final String DIM_DEAL_TYPE = "DEAL_TYPE";
    public static final String DIM_MILESTONE = "MILESTONE";
    public static final String DIM_PARTY_TYPE = "PARTY_TYPE";

    /**
     * List of all valid dimension types.
     */
    public static final List<String> VALID_DIMENSION_TYPES = Arrays.asList(
        DIM_CURRENCY, DIM_REGION, DIM_COUNTRY, DIM_STATE, DIM_CITY,
        DIM_ENTITY, DIM_DEPARTMENT, DIM_COST_CENTER, DIM_ACCOUNT_TYPE,
        DIM_VIRTUAL_ACCOUNT, DIM_CHANNEL, DIM_PLATFORM, DIM_SEGMENT,
        DIM_CUSTOMER, DIM_MERCHANT, DIM_PARTNER, DIM_TIER, DIM_BATCH,
        DIM_DENOMINATION, DIM_CARD_TYPE, DIM_CARD_FORMAT, DIM_NETWORK,
        DIM_KYC_TIER, DIM_BUDGET_OWNER, DIM_POINTS_STATUS, DIM_DEAL_TYPE,
        DIM_MILESTONE, DIM_PARTY_TYPE
    );

    // ========================================================================
    // Default icons for dimension types
    // ========================================================================
    
    public static String getDefaultIcon(String dimensionType) {
        return switch (dimensionType) {
            case DIM_CURRENCY -> "currency-dollar";
            case DIM_REGION, DIM_COUNTRY, DIM_STATE, DIM_CITY -> "globe";
            case DIM_ENTITY -> "building";
            case DIM_DEPARTMENT, DIM_COST_CENTER -> "briefcase";
            case DIM_ACCOUNT_TYPE -> "folder";
            case DIM_VIRTUAL_ACCOUNT -> "credit-card";
            case DIM_CHANNEL, DIM_PLATFORM -> "device-mobile";
            case DIM_SEGMENT, DIM_CUSTOMER -> "users";
            case DIM_MERCHANT -> "shopping-cart";
            case DIM_PARTNER -> "handshake";
            case DIM_TIER -> "star";
            case DIM_BATCH -> "box";
            case DIM_DENOMINATION -> "cash";
            case DIM_CARD_TYPE, DIM_CARD_FORMAT -> "credit-card";
            case DIM_NETWORK -> "share-2";
            case DIM_KYC_TIER -> "shield-check";
            case DIM_BUDGET_OWNER -> "user-circle";
            case DIM_POINTS_STATUS -> "gift";
            case DIM_DEAL_TYPE -> "file-text";
            case DIM_MILESTONE -> "flag";
            case DIM_PARTY_TYPE -> "user";
            default -> "folder";
        };
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    /**
     * Check if a value is allowed for this level.
     */
    public boolean isValueAllowed(String value) {
        if (allowedValues == null || allowedValues.isEmpty()) {
            return true; // Any value allowed
        }
        // Simple check - in production, parse JSON properly
        return allowedValues.contains("\"" + value + "\"");
    }

    /**
     * Check if this is the root level (Level 1).
     */
    public boolean isRootLevel() {
        return levelNumber != null && levelNumber == 1;
    }

    /**
     * Check if this is the leaf level (last level in the hierarchy).
     * Now dynamic based on maxDepth instead of hardcoded 7.
     */
    public boolean isLeafLevel() {
        int effectiveMaxDepth = maxDepth != null ? maxDepth : HierarchyNode.DEFAULT_MAX_DEPTH;
        return levelNumber != null && levelNumber.equals(effectiveMaxDepth);
    }

    /**
     * Check if this is a consolidation level (between root and leaf).
     */
    public boolean isConsolidationLevel() {
        int effectiveMaxDepth = maxDepth != null ? maxDepth : HierarchyNode.DEFAULT_MAX_DEPTH;
        return levelNumber != null && levelNumber > 1 && levelNumber < effectiveMaxDepth;
    }

    // ========================================================================
    // Validation
    // ========================================================================

    @PrePersist
    @PreUpdate
    private void validate() {
        // Initialize maxDepth if not set
        if (maxDepth == null) {
            maxDepth = HierarchyNode.DEFAULT_MAX_DEPTH;
        }

        // Ensure maxDepth is within absolute limits
        if (maxDepth < 2 || maxDepth > HierarchyNode.ABSOLUTE_MAX_DEPTH) {
            throw new IllegalArgumentException(
                "Max depth must be between 2 and " + HierarchyNode.ABSOLUTE_MAX_DEPTH);
        }

        // Level number validation (dynamic based on maxDepth)
        if (levelNumber == null || levelNumber < 1 || levelNumber > maxDepth) {
            throw new IllegalArgumentException(
                "Level number must be between 1 and " + maxDepth + ", got: " + levelNumber);
        }
        
        if (levelName == null || levelName.trim().isEmpty()) {
            throw new IllegalArgumentException("Level name is required");
        }
        
        if (dimensionType == null || dimensionType.trim().isEmpty()) {
            throw new IllegalArgumentException("Dimension type is required");
        }
        
        // Set default display order if not provided
        if (displayOrder == null) {
            displayOrder = levelNumber;
        }
        
        // Set default icon if not provided
        if (icon == null || icon.trim().isEmpty()) {
            icon = getDefaultIcon(dimensionType);
        }
    }

    // ========================================================================
    // Builder factory methods for common templates
    // ========================================================================

    /**
     * Creates level config for IHB (In-House Bank) program.
     */
    public static HierarchyLevelConfig createIhbConfig(UUID programId, int level) {
        return switch (level) {
            case 1 -> HierarchyLevelConfig.builder()
                .programId(programId)
                .levelNumber(1)
                .levelName("Currency")
                .dimensionType(DIM_CURRENCY)
                .isRequired(true)
                .description("Master account currency")
                .build();
            case 2 -> HierarchyLevelConfig.builder()
                .programId(programId)
                .levelNumber(2)
                .levelName("Region")
                .dimensionType(DIM_REGION)
                .isRequired(true)
                .allowedValues("[\"NORTH\", \"SOUTH\", \"EAST\", \"WEST\"]")
                .description("Geographic region")
                .build();
            case 3 -> HierarchyLevelConfig.builder()
                .programId(programId)
                .levelNumber(3)
                .levelName("State")
                .dimensionType(DIM_STATE)
                .isRequired(true)
                .description("State or province")
                .build();
            case 4 -> HierarchyLevelConfig.builder()
                .programId(programId)
                .levelNumber(4)
                .levelName("City")
                .dimensionType(DIM_CITY)
                .isRequired(true)
                .description("City location")
                .build();
            case 5 -> HierarchyLevelConfig.builder()
                .programId(programId)
                .levelNumber(5)
                .levelName("Entity")
                .dimensionType(DIM_ENTITY)
                .isRequired(true)
                .description("Legal entity or subsidiary")
                .build();
            case 6 -> HierarchyLevelConfig.builder()
                .programId(programId)
                .levelNumber(6)
                .levelName("Account Type")
                .dimensionType(DIM_ACCOUNT_TYPE)
                .isRequired(true)
                .allowedValues("[\"PAYABLES\", \"RECEIVABLES\", \"TAXES\", \"PAYROLL\", \"CAPEX\", \"INTERCOMPANY\"]")
                .description("Functional account type")
                .build();
            case 7 -> HierarchyLevelConfig.builder()
                .programId(programId)
                .levelNumber(7)
                .levelName("Virtual Account")
                .dimensionType(DIM_VIRTUAL_ACCOUNT)
                .isRequired(true)
                .description("Virtual account (leaf node)")
                .build();
            default -> throw new IllegalArgumentException("Invalid level: " + level);
        };
    }

    /**
     * Creates level config for Collections program.
     */
    public static HierarchyLevelConfig createCollectionsConfig(UUID programId, int level) {
        return switch (level) {
            case 1 -> HierarchyLevelConfig.builder()
                .programId(programId)
                .levelNumber(1)
                .levelName("Currency")
                .dimensionType(DIM_CURRENCY)
                .isRequired(true)
                .description("Master account currency")
                .build();
            case 2 -> HierarchyLevelConfig.builder()
                .programId(programId)
                .levelNumber(2)
                .levelName("Collection Channel")
                .dimensionType(DIM_CHANNEL)
                .isRequired(true)
                .allowedValues("[\"INVOICE\", \"ECOMMERCE\", \"POS\", \"DIRECT\"]")
                .description("Collection channel type")
                .build();
            case 3 -> HierarchyLevelConfig.builder()
                .programId(programId)
                .levelNumber(3)
                .levelName("Region/Platform")
                .dimensionType(DIM_PLATFORM)
                .isRequired(true)
                .description("Region or e-commerce platform")
                .build();
            case 4 -> HierarchyLevelConfig.builder()
                .programId(programId)
                .levelNumber(4)
                .levelName("Segment/Category")
                .dimensionType(DIM_SEGMENT)
                .isRequired(true)
                .description("Customer segment or product category")
                .build();
            case 5 -> HierarchyLevelConfig.builder()
                .programId(programId)
                .levelNumber(5)
                .levelName("Customer/Seller")
                .dimensionType(DIM_CUSTOMER)
                .isRequired(true)
                .description("Customer or seller identifier")
                .build();
            case 6 -> HierarchyLevelConfig.builder()
                .programId(programId)
                .levelNumber(6)
                .levelName("Account Type")
                .dimensionType(DIM_ACCOUNT_TYPE)
                .isRequired(true)
                .description("Account function type")
                .build();
            case 7 -> HierarchyLevelConfig.builder()
                .programId(programId)
                .levelNumber(7)
                .levelName("Virtual Account")
                .dimensionType(DIM_VIRTUAL_ACCOUNT)
                .isRequired(true)
                .description("Virtual account (leaf node)")
                .build();
            default -> throw new IllegalArgumentException("Invalid level: " + level);
        };
    }
}