-- ============================================================================
-- CASH CONCENTRATION SIMULATOR - PHASE 2
-- Migration V6: bank_fee_tariff
-- ============================================================================
--
-- Manually-curated per-bank/per-rail fee tariff, consumed by the Phase-2
-- "Bank fees" score line. Source is tagged ('ESTIMATE' until a real tariff
-- sheet is loaded) so the "View assumptions" drawer can disclose provenance.
--
-- Same deployment caveat as V5: `spring.flyway.enabled=false` +
-- `ddl-auto=update`, so the JPA entity
-- (com.bank.vam.entity.simulator.BankFeeTariff) creates this table at runtime.
-- This file is the canonical DDL / fresh-bootstrap path and MUST stay in
-- lockstep with that entity.
-- ============================================================================

CREATE TABLE IF NOT EXISTS bank_fee_tariff (
    id              UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),

    bank_code       VARCHAR(20)  NOT NULL,
    payment_rail    VARCHAR(20)  NOT NULL,   -- BANCS | SWIFT_MT103 | SEPA | RTGS | OPEN_BANKING
    fee_currency    VARCHAR(3)   NOT NULL,
    fee_fixed       NUMERIC(12,4) DEFAULT 0, -- per-transaction fixed fee
    fee_bps         NUMERIC(8,4)  DEFAULT 0, -- basis points on principal
    effective_from  DATE         NOT NULL,
    effective_to    DATE,
    source          VARCHAR(50),             -- 'TARIFF_SHEET_2026' | 'ESTIMATE' | ...

    -- BaseEntity audit columns (mapped by com.bank.vam.entity.BaseEntity)
    created_at      TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    created_by      VARCHAR(100),
    updated_at      TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_by      VARCHAR(100),
    version         BIGINT       DEFAULT 0,

    CONSTRAINT uq_bank_fee_tariff UNIQUE (bank_code, payment_rail, effective_from)
);

CREATE INDEX IF NOT EXISTS idx_bft_bank      ON bank_fee_tariff (bank_code);
CREATE INDEX IF NOT EXISTS idx_bft_effective ON bank_fee_tariff (effective_from, effective_to);

COMMENT ON TABLE  bank_fee_tariff             IS 'Per-bank/per-rail fee tariff for the Simulator Bank-fees score line (Phase 2).';
COMMENT ON COLUMN bank_fee_tariff.fee_bps     IS 'Basis points charged on principal (÷10000).';
COMMENT ON COLUMN bank_fee_tariff.source      IS 'Provenance, disclosed in the Optimisation Score "View assumptions" drawer.';
