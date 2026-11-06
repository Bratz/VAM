package com.bank.vam.fx.deal;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * A standing settlement instruction (SSI): the account an entity's FX deals settle through in one currency. The
 * account is one of the entity's bank-account mirrors at the home bank (the dealer, which can post to it). One per
 * entity and currency; without one, a deal uses the entity's only home-bank account in that currency.
 */
@Entity
@Table(name = "fx_settlement_instruction",
        uniqueConstraints = @UniqueConstraint(name = "uk_fx_ssi_entity_currency", columnNames = {"entity_id", "currency"}))
@Getter
@Setter
@NoArgsConstructor
public class FxSettlementInstruction extends BaseEntity {

    @Column(name = "corporate_id", nullable = false)
    private UUID corporateId;

    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    /** The bank-account mirror (a virtual account of category PHYSICAL_MIRROR) settlements post to. */
    @Column(name = "account_id", nullable = false)
    private UUID accountId;
}
