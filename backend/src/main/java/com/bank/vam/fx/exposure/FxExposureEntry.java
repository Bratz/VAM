package com.bank.vam.fx.exposure;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * An exposure or an existing hedge the corporate told us about, because it does not live in
 * payables, receivables or account balances (ERP forecasts, forwards booked with another bank).
 *
 * <p>{@code amount} is signed in the entry's own currency. For EXPOSURE, positive means the
 * corporate will receive that currency and negative that it will pay it. For HEDGE it is the
 * hedge deal's own position: selling EUR forward is negative.
 */
@Entity
@Table(name = "fx_exposure_entry", indexes = @Index(name = "idx_fx_exposure_entry_corporate", columnList = "corporate_id"))
@Getter
@Setter
@NoArgsConstructor
public class FxExposureEntry extends BaseEntity {

    public enum Kind { EXPOSURE, HEDGE }

    public static final String SOURCE_UPLOAD = "UPLOAD";

    @Column(name = "corporate_id", nullable = false)
    private UUID corporateId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 10)
    private Kind kind;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "value_date", nullable = false)
    private LocalDate valueDate;

    @Column(name = "reference", length = 100)
    private String reference;

    @Column(name = "description", length = 200)
    private String description;

    @Column(name = "source", nullable = false, length = 20)
    private String source = SOURCE_UPLOAD;
}
