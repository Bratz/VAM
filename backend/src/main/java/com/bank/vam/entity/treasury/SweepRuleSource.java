package com.bank.vam.entity.treasury;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Entity
@Table(name = "sweep_rule_sources")
public class SweepRuleSource {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rule_id", nullable = false)
    private SweepRule rule;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "account_number", nullable = false, length = 34)
    private String accountNumber;

    @Column(name = "entity_code", length = 20)
    private String entityCode;

    @Column(name = "entity_name", length = 100)
    private String entityName;

    @Column(name = "currency_code", length = 3)
    private String currencyCode = "AED";

    @Column(name = "bank_name", length = 100)
    private String bankName;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();
}
