package com.bank.vam.fx.deal;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/** The most FX credit exposure the bank will run to one corporate, in that corporate's home currency. */
@Entity
@Table(name = "fx_credit_limit")
@Getter
@Setter
@NoArgsConstructor
public class FxCreditLimit extends BaseEntity {

    @Column(name = "corporate_id", nullable = false, unique = true)
    private UUID corporateId;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "limit_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal limitAmount;
}
