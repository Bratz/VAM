--
-- PostgreSQL database dump
--

\restrict hWi2gbpEFU7uv0ZGbZLDbvKIg17Ls4uoZFcYZu3ei6VZtBavSiOqdfZ9VoA3ddv

-- Dumped from database version 18.0
-- Dumped by pg_dump version 18.0

-- Started on 2026-01-06 11:11:53

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- TOC entry 4 (class 3079 OID 32963)
-- Name: pg_trgm; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;


--
-- TOC entry 6851 (class 0 OID 0)
-- Dependencies: 4
-- Name: EXTENSION pg_trgm; Type: COMMENT; Schema: -; Owner: 
--

COMMENT ON EXTENSION pg_trgm IS 'text similarity measurement and index searching based on trigrams';


--
-- TOC entry 3 (class 3079 OID 29733)
-- Name: pgcrypto; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public;


--
-- TOC entry 6852 (class 0 OID 0)
-- Dependencies: 3
-- Name: EXTENSION pgcrypto; Type: COMMENT; Schema: -; Owner: 
--

COMMENT ON EXTENSION pgcrypto IS 'cryptographic functions';


--
-- TOC entry 2 (class 3079 OID 29722)
-- Name: uuid-ossp; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS "uuid-ossp" WITH SCHEMA public;


--
-- TOC entry 6853 (class 0 OID 0)
-- Dependencies: 2
-- Name: EXTENSION "uuid-ossp"; Type: COMMENT; Schema: -; Owner: 
--

COMMENT ON EXTENSION "uuid-ossp" IS 'generate universally unique identifiers (UUIDs)';


--
-- TOC entry 1322 (class 1247 OID 51458)
-- Name: netting_status; Type: TYPE; Schema: public; Owner: postgres
--

CREATE TYPE public.netting_status AS ENUM (
    'NOT_INCLUDED',
    'PENDING',
    'INCLUDED',
    'SETTLED',
    'REMOVED'
);


ALTER TYPE public.netting_status OWNER TO postgres;

--
-- TOC entry 1310 (class 1247 OID 51392)
-- Name: payable_status; Type: TYPE; Schema: public; Owner: postgres
--

CREATE TYPE public.payable_status AS ENUM (
    'DRAFT',
    'PENDING_APPROVAL',
    'APPROVED',
    'PENDING_POBO',
    'POBO_APPROVED',
    'SCHEDULED',
    'PENDING_NETTING',
    'PROCESSING',
    'PAID',
    'PARTIAL_PAID',
    'NETTED',
    'CANCELLED',
    'REJECTED',
    'ON_HOLD'
);


ALTER TYPE public.payable_status OWNER TO postgres;

--
-- TOC entry 1307 (class 1247 OID 51370)
-- Name: payable_type; Type: TYPE; Schema: public; Owner: postgres
--

CREATE TYPE public.payable_type AS ENUM (
    'INVOICE',
    'CREDIT_NOTE',
    'DEBIT_NOTE',
    'EXPENSE',
    'ADVANCE',
    'DEPOSIT',
    'UTILITY',
    'TAX_PAYMENT',
    'SALARY',
    'INTERCOMPANY'
);


ALTER TYPE public.payable_type OWNER TO postgres;

--
-- TOC entry 1331 (class 1247 OID 51500)
-- Name: payment_channel; Type: TYPE; Schema: public; Owner: postgres
--

CREATE TYPE public.payment_channel AS ENUM (
    'SWIFT',
    'LOCAL_CLEARING',
    'RTGS',
    'ACH',
    'SEPA',
    'INTERNAL',
    'CARD_NETWORK',
    'NETTING'
);


ALTER TYPE public.payment_channel OWNER TO postgres;

--
-- TOC entry 1328 (class 1247 OID 51482)
-- Name: payment_method; Type: TYPE; Schema: public; Owner: postgres
--

CREATE TYPE public.payment_method AS ENUM (
    'BANK_TRANSFER',
    'WIRE',
    'ACH',
    'SEPA',
    'CHECK',
    'CARD',
    'INTERNAL_TRANSFER',
    'NETTING_SETTLEMENT'
);


ALTER TYPE public.payment_method OWNER TO postgres;

--
-- TOC entry 1325 (class 1247 OID 51470)
-- Name: payment_priority; Type: TYPE; Schema: public; Owner: postgres
--

CREATE TYPE public.payment_priority AS ENUM (
    'LOW',
    'NORMAL',
    'HIGH',
    'URGENT',
    'CRITICAL'
);


ALTER TYPE public.payment_priority OWNER TO postgres;

--
-- TOC entry 1316 (class 1247 OID 51434)
-- Name: payment_route; Type: TYPE; Schema: public; Owner: postgres
--

CREATE TYPE public.payment_route AS ENUM (
    'DIRECT',
    'POBO',
    'INTERCOMPANY',
    'NETTING'
);


ALTER TYPE public.payment_route OWNER TO postgres;

--
-- TOC entry 1313 (class 1247 OID 51422)
-- Name: payment_status; Type: TYPE; Schema: public; Owner: postgres
--

CREATE TYPE public.payment_status AS ENUM (
    'NOT_PAID',
    'PARTIALLY_PAID',
    'PAID',
    'OVERPAID',
    'REFUNDED'
);


ALTER TYPE public.payment_status OWNER TO postgres;

--
-- TOC entry 1319 (class 1247 OID 51444)
-- Name: pobo_request_status; Type: TYPE; Schema: public; Owner: postgres
--

CREATE TYPE public.pobo_request_status AS ENUM (
    'NOT_REQUESTED',
    'PENDING_TREASURY_APPROVAL',
    'APPROVED',
    'REJECTED',
    'EXECUTED',
    'CANCELLED'
);


ALTER TYPE public.pobo_request_status OWNER TO postgres;

--
-- TOC entry 1065 (class 1247 OID 29814)
-- Name: reconciliation_match_method; Type: TYPE; Schema: public; Owner: vam_user
--

CREATE TYPE public.reconciliation_match_method AS ENUM (
    'AUTO_IBAN',
    'AUTO_E2E_REF',
    'AUTO_AMOUNT',
    'AUTO_COMPOSITE',
    'MANUAL',
    'RULE_BASED',
    'ML_SUGGESTED'
);


ALTER TYPE public.reconciliation_match_method OWNER TO vam_user;

--
-- TOC entry 1062 (class 1247 OID 29798)
-- Name: reconciliation_match_status; Type: TYPE; Schema: public; Owner: vam_user
--

CREATE TYPE public.reconciliation_match_status AS ENUM (
    'EXACT_MATCH',
    'PARTIAL_MATCH',
    'OVERPAID',
    'UNDERPAID',
    'UNMATCHED',
    'MANUAL_MATCH',
    'DISPUTED'
);


ALTER TYPE public.reconciliation_match_status OWNER TO vam_user;

--
-- TOC entry 383 (class 1255 OID 30231)
-- Name: allocate_ecommerce_iban(uuid, character varying, numeric, character varying, integer, jsonb); Type: FUNCTION; Schema: public; Owner: vam_user
--

CREATE FUNCTION public.allocate_ecommerce_iban(p_merchant_id uuid, p_order_reference character varying, p_expected_amount numeric, p_currency character varying, p_expiry_hours integer, p_metadata jsonb DEFAULT NULL::jsonb) RETURNS TABLE(iban character varying, ecommerce_iban_id uuid, expires_at timestamp without time zone)
    LANGUAGE plpgsql
    AS $$
DECLARE
    v_pool_iban RECORD;
    v_merchant RECORD;
    v_va_id UUID;
    v_ecom_id UUID;
    v_expires TIMESTAMP;
BEGIN
    -- Get merchant config
    SELECT * INTO v_merchant FROM ecommerce_merchants WHERE id = p_merchant_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Merchant not found: %', p_merchant_id;
    END IF;
    
    -- Check daily limit
    IF (SELECT COUNT(*) FROM ecommerce_virtual_ibans 
        WHERE merchant_id = p_merchant_id 
        AND DATE(created_at) = CURRENT_DATE) >= v_merchant.daily_iban_limit THEN
        RAISE EXCEPTION 'Daily IBAN limit exceeded for merchant %', v_merchant.merchant_code;
    END IF;
    
    -- Allocate from pool
    SELECT * INTO v_pool_iban 
    FROM iban_pool 
    WHERE merchant_id = p_merchant_id AND status = 'AVAILABLE'
    ORDER BY id
    LIMIT 1
    FOR UPDATE SKIP LOCKED;
    
    IF NOT FOUND THEN
        RAISE EXCEPTION 'No available IBANs in pool for merchant %', v_merchant.merchant_code;
    END IF;
    
    -- Calculate expiry
    v_expires := CURRENT_TIMESTAMP + (COALESCE(p_expiry_hours, v_merchant.default_expiry_hours) || ' hours')::INTERVAL;
    
    -- Create virtual account
    INSERT INTO virtual_accounts (
        virtual_account_number, virtual_iban, corporate_scheme_id, customer_id,
        account_name, currency_code, account_type, account_purpose, status
    ) VALUES (
        v_pool_iban.virtual_account_number, 
        v_pool_iban.virtual_iban,
        v_merchant.scheme_id,
        v_merchant.customer_id,
        'ECOM-' || p_order_reference,
        p_currency,
        'ECOMMERCE',
        'ECOMMERCE_ORDER',
        1
    ) RETURNING id INTO v_va_id;
    
    -- Create e-commerce IBAN record
    INSERT INTO ecommerce_virtual_ibans (
        merchant_id, virtual_account_id, order_reference,
        virtual_iban, expected_amount, currency_code,
        expires_at, status, metadata
    ) VALUES (
        p_merchant_id, v_va_id, p_order_reference,
        v_pool_iban.virtual_iban, p_expected_amount, p_currency,
        v_expires, 'ACTIVE', p_metadata
    ) RETURNING id INTO v_ecom_id;
    
    -- Update pool
    UPDATE iban_pool 
    SET status = 'ALLOCATED', allocated_to = v_ecom_id, allocated_at = CURRENT_TIMESTAMP,
        use_count = use_count + 1, last_used_at = CURRENT_TIMESTAMP
    WHERE id = v_pool_iban.id;
    
    -- Update VA with ecommerce link
    UPDATE virtual_accounts SET ecommerce_iban_id = v_ecom_id WHERE id = v_va_id;
    
    RETURN QUERY SELECT v_pool_iban.virtual_iban, v_ecom_id, v_expires;
END;
$$;


ALTER FUNCTION public.allocate_ecommerce_iban(p_merchant_id uuid, p_order_reference character varying, p_expected_amount numeric, p_currency character varying, p_expiry_hours integer, p_metadata jsonb) OWNER TO vam_user;

--
-- TOC entry 341 (class 1255 OID 34038)
-- Name: calculate_charge_amount(numeric, character varying); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE FUNCTION public.calculate_charge_amount(p_base_amount numeric, p_charge_code character varying) RETURNS numeric
    LANGUAGE plpgsql
    AS $$
DECLARE
    v_config RECORD;
    v_result DECIMAL(18,2);
BEGIN
    SELECT * INTO v_config
    FROM charge_configurations
    WHERE charge_code = p_charge_code
      AND status = 'ACTIVE'
      AND (effective_to IS NULL OR effective_to >= CURRENT_DATE);
    
    IF v_config IS NULL THEN
        RETURN 0;
    END IF;
    
    -- Calculate based on category
    IF v_config.charge_category = 'FIXED' THEN
        v_result := v_config.fixed_amount;
    ELSIF v_config.charge_category = 'PERCENTAGE' THEN
        v_result := p_base_amount * v_config.percentage_rate / 100;
    ELSE
        v_result := v_config.fixed_amount + (p_base_amount * v_config.percentage_rate / 100);
    END IF;
    
    -- Apply bounds
    IF v_config.minimum_charge IS NOT NULL AND v_result < v_config.minimum_charge THEN
        v_result := v_config.minimum_charge;
    END IF;
    IF v_config.maximum_charge IS NOT NULL AND v_result > v_config.maximum_charge THEN
        v_result := v_config.maximum_charge;
    END IF;
    
    RETURN ROUND(v_result, 2);
END;
$$;


ALTER FUNCTION public.calculate_charge_amount(p_base_amount numeric, p_charge_code character varying) OWNER TO postgres;

--
-- TOC entry 6856 (class 0 OID 0)
-- Dependencies: 341
-- Name: FUNCTION calculate_charge_amount(p_base_amount numeric, p_charge_code character varying); Type: COMMENT; Schema: public; Owner: postgres
--

COMMENT ON FUNCTION public.calculate_charge_amount(p_base_amount numeric, p_charge_code character varying) IS 'Calculate charge amount based on charge code';


--
-- TOC entry 357 (class 1255 OID 30233)
-- Name: calculate_loan_interest(uuid, date); Type: FUNCTION; Schema: public; Owner: vam_user
--

CREATE FUNCTION public.calculate_loan_interest(p_loan_id uuid, p_calculation_date date DEFAULT CURRENT_DATE) RETURNS TABLE(interest_amount numeric, days integer, rate numeric, principal numeric)
    LANGUAGE plpgsql
    AS $$
DECLARE
    v_loan RECORD;
    v_last_calc_date DATE;
    v_days INTEGER;
    v_interest DECIMAL(18,2);
BEGIN
    -- Get loan details
    SELECT * INTO v_loan FROM intercompany_loans WHERE id = p_loan_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Loan not found: %', p_loan_id;
    END IF;
    
    -- Get last interest calculation date
    SELECT COALESCE(MAX(period_to), v_loan.disbursement_date) INTO v_last_calc_date
    FROM intercompany_loan_transactions
    WHERE loan_id = p_loan_id AND transaction_type = 'INTEREST_ACCRUAL';
    
    -- Calculate days
    v_days := p_calculation_date - v_last_calc_date;
    
    IF v_days <= 0 THEN
        RETURN QUERY SELECT 0::DECIMAL(18,2), 0, v_loan.interest_rate, v_loan.outstanding_principal;
        RETURN;
    END IF;
    
    -- Calculate interest based on day count basis
    CASE v_loan.day_count_basis
        WHEN 'ACT_360' THEN
            v_interest := v_loan.outstanding_principal * v_loan.interest_rate / 100 * v_days / 360;
        WHEN 'ACT_365' THEN
            v_interest := v_loan.outstanding_principal * v_loan.interest_rate / 100 * v_days / 365;
        WHEN '30_360' THEN
            v_interest := v_loan.outstanding_principal * v_loan.interest_rate / 100 * v_days / 360;
        ELSE
            v_interest := v_loan.outstanding_principal * v_loan.interest_rate / 100 * v_days / 360;
    END CASE;
    
    RETURN QUERY SELECT ROUND(v_interest, 2), v_days, v_loan.interest_rate, v_loan.outstanding_principal;
END;
$$;


ALTER FUNCTION public.calculate_loan_interest(p_loan_id uuid, p_calculation_date date) OWNER TO vam_user;

--
-- TOC entry 340 (class 1255 OID 33152)
-- Name: calculate_node_balance(uuid); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE FUNCTION public.calculate_node_balance(p_node_id uuid) RETURNS numeric
    LANGUAGE plpgsql
    AS $$
DECLARE
    v_balance DECIMAL;
    v_path VARCHAR;
    v_program_id UUID;
    v_is_leaf BOOLEAN;
BEGIN
    -- Get node info
    SELECT h.materialized_path, h.program_id, h.is_leaf
    INTO v_path, v_program_id, v_is_leaf
    FROM hierarchy_nodes h 
    WHERE h.id = p_node_id;
    
    IF v_is_leaf THEN
        -- For leaf nodes, get VA balance
        SELECT COALESCE(va.current_balance, 0)
        INTO v_balance
        FROM hierarchy_nodes h
        LEFT JOIN virtual_accounts va ON h.virtual_account_id = va.id
        WHERE h.id = p_node_id;
    ELSE
        -- For non-leaf nodes, sum descendant leaf balances
        SELECT COALESCE(SUM(va.current_balance), 0)
        INTO v_balance
        FROM hierarchy_nodes h
        JOIN virtual_accounts va ON h.virtual_account_id = va.id
        WHERE h.program_id = v_program_id
          AND h.materialized_path LIKE v_path || '/%'
          AND h.is_leaf = true;
    END IF;
    
    RETURN COALESCE(v_balance, 0);
END;
$$;


ALTER FUNCTION public.calculate_node_balance(p_node_id uuid) OWNER TO postgres;

--
-- TOC entry 436 (class 1255 OID 51523)
-- Name: calculate_payable_amounts(); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE FUNCTION public.calculate_payable_amounts() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    -- Calculate net amount
    NEW.net_amount := NEW.gross_amount - COALESCE(NEW.discount_amount, 0) + COALESCE(NEW.tax_amount, 0) - COALESCE(NEW.withholding_tax, 0);
    
    -- Calculate outstanding amount
    NEW.outstanding_amount := NEW.net_amount - COALESCE(NEW.paid_amount, 0);
    
    RETURN NEW;
END;
$$;


ALTER FUNCTION public.calculate_payable_amounts() OWNER TO postgres;

--
-- TOC entry 344 (class 1255 OID 30234)
-- Name: calculate_pool_position(uuid); Type: FUNCTION; Schema: public; Owner: vam_user
--

CREATE FUNCTION public.calculate_pool_position(p_pool_id uuid) RETURNS TABLE(total_credit numeric, total_debit numeric, net_position numeric, member_count integer, interest_advantage numeric)
    LANGUAGE plpgsql
    AS $$
DECLARE
    v_pool RECORD;
    v_total_credit DECIMAL(18,2) := 0;
    v_total_debit DECIMAL(18,2) := 0;
    v_standalone_interest DECIMAL(18,2) := 0;
    v_pooled_interest DECIMAL(18,2) := 0;
    v_member_count INTEGER := 0;
BEGIN
    -- Get pool config
    SELECT * INTO v_pool FROM notional_pools WHERE id = p_pool_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Pool not found: %', p_pool_id;
    END IF;
    
    -- Calculate positions from members
    SELECT 
        COUNT(*),
        COALESCE(SUM(CASE WHEN balance_in_pool_currency >= 0 THEN balance_in_pool_currency ELSE 0 END), 0),
        COALESCE(SUM(CASE WHEN balance_in_pool_currency < 0 THEN ABS(balance_in_pool_currency) ELSE 0 END), 0)
    INTO v_member_count, v_total_credit, v_total_debit
    FROM notional_pool_members
    WHERE pool_id = p_pool_id AND status = 'ACTIVE';
    
    -- Calculate standalone interest (what each would pay/earn individually)
    SELECT COALESCE(SUM(
        CASE 
            WHEN npm.balance_in_pool_currency >= 0 
            THEN npm.balance_in_pool_currency * COALESCE(npm.custom_credit_rate, v_pool.credit_interest_rate) / 100 / 360
            ELSE ABS(npm.balance_in_pool_currency) * COALESCE(npm.custom_debit_rate, v_pool.debit_interest_rate) / 100 / 360
        END
    ), 0) INTO v_standalone_interest
    FROM notional_pool_members npm
    WHERE npm.pool_id = p_pool_id AND npm.status = 'ACTIVE';
    
    -- Calculate pooled interest (on net position)
    IF (v_total_credit - v_total_debit) >= 0 THEN
        v_pooled_interest := (v_total_credit - v_total_debit) * v_pool.credit_interest_rate / 100 / 360;
    ELSE
        v_pooled_interest := ABS(v_total_credit - v_total_debit) * v_pool.debit_interest_rate / 100 / 360;
    END IF;
    
    -- Update pool
    UPDATE notional_pools
    SET total_credit_balance = v_total_credit,
        total_debit_balance = v_total_debit,
        net_pool_position = v_total_credit - v_total_debit,
        last_position_update = CURRENT_TIMESTAMP
    WHERE id = p_pool_id;
    
    RETURN QUERY SELECT 
        v_total_credit, 
        v_total_debit, 
        v_total_credit - v_total_debit,
        v_member_count,
        ABS(v_standalone_interest - v_pooled_interest);
END;
$$;


ALTER FUNCTION public.calculate_pool_position(p_pool_id uuid) OWNER TO vam_user;

--
-- TOC entry 410 (class 1255 OID 42323)
-- Name: calculate_program_charge(uuid, character varying, numeric); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE FUNCTION public.calculate_program_charge(p_program_id uuid, p_charge_code character varying, p_amount numeric) RETURNS numeric
    LANGUAGE plpgsql
    AS $$
DECLARE
    v_base_config RECORD;
    v_override RECORD;
    v_charge NUMERIC(18,2);
    v_percentage NUMERIC(8,4);
    v_fixed NUMERIC(18,2);
    v_minimum NUMERIC(18,2);
    v_maximum NUMERIC(18,2);
BEGIN
    -- Get base configuration
    SELECT * INTO v_base_config 
    FROM charge_configurations 
    WHERE charge_code = p_charge_code 
      AND status = 'ACTIVE'
      AND (effective_to IS NULL OR effective_to >= CURRENT_DATE)
    LIMIT 1;
    
    IF NOT FOUND THEN
        RETURN 0;
    END IF;
    
    -- Get program override if exists
    SELECT * INTO v_override
    FROM program_charge_overrides
    WHERE program_id = p_program_id
      AND charge_code = p_charge_code
      AND status = 'ACTIVE'
      AND (effective_to IS NULL OR effective_to >= CURRENT_DATE)
    LIMIT 1;
    
    -- Check if waived
    IF v_override IS NOT NULL AND v_override.is_waived THEN
        RETURN 0;
    END IF;
    
    -- Determine effective values
    v_percentage := COALESCE(v_override.override_percentage, v_base_config.percentage_rate);
    v_fixed := COALESCE(v_override.override_fixed, v_base_config.fixed_amount);
    v_minimum := COALESCE(v_override.override_minimum, v_base_config.minimum_charge);
    v_maximum := COALESCE(v_override.override_maximum, v_base_config.maximum_charge);
    
    -- Calculate charge based on category
    CASE v_base_config.charge_category
        WHEN 'FIXED' THEN
            v_charge := v_fixed;
        WHEN 'PERCENTAGE' THEN
            v_charge := p_amount * v_percentage / 100;
        ELSE
            -- Combined: fixed + percentage
            v_charge := v_fixed + (p_amount * v_percentage / 100);
    END CASE;
    
    -- Apply bounds
    IF v_minimum IS NOT NULL AND v_charge < v_minimum THEN
        v_charge := v_minimum;
    END IF;
    IF v_maximum IS NOT NULL AND v_charge > v_maximum THEN
        v_charge := v_maximum;
    END IF;
    
    RETURN ROUND(v_charge, 2);
END;
$$;


ALTER FUNCTION public.calculate_program_charge(p_program_id uuid, p_charge_code character varying, p_amount numeric) OWNER TO postgres;

--
-- TOC entry 6860 (class 0 OID 0)
-- Dependencies: 410
-- Name: FUNCTION calculate_program_charge(p_program_id uuid, p_charge_code character varying, p_amount numeric); Type: COMMENT; Schema: public; Owner: postgres
--

COMMENT ON FUNCTION public.calculate_program_charge(p_program_id uuid, p_charge_code character varying, p_amount numeric) IS 'Calculate charge for a program with override support';


--
-- TOC entry 397 (class 1255 OID 34037)
-- Name: calculate_tax_amount(numeric, character varying); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE FUNCTION public.calculate_tax_amount(p_base_amount numeric, p_tax_code character varying) RETURNS numeric
    LANGUAGE plpgsql
    AS $$
DECLARE
    v_rate DECIMAL(8,4);
    v_result DECIMAL(18,2);
BEGIN
    SELECT rate_percentage INTO v_rate
    FROM tax_configurations
    WHERE tax_code = p_tax_code
      AND status = 'ACTIVE'
      AND (effective_to IS NULL OR effective_to >= CURRENT_DATE);
    
    IF v_rate IS NULL THEN
        RETURN 0;
    END IF;
    
    v_result := p_base_amount * v_rate / 100;
    RETURN ROUND(v_result, 2);
END;
$$;


ALTER FUNCTION public.calculate_tax_amount(p_base_amount numeric, p_tax_code character varying) OWNER TO postgres;

--
-- TOC entry 6862 (class 0 OID 0)
-- Dependencies: 397
-- Name: FUNCTION calculate_tax_amount(p_base_amount numeric, p_tax_code character varying); Type: COMMENT; Schema: public; Owner: postgres
--

COMMENT ON FUNCTION public.calculate_tax_amount(p_base_amount numeric, p_tax_code character varying) IS 'Calculate tax amount based on tax code';


--
-- TOC entry 373 (class 1255 OID 50829)
-- Name: can_va_debit(uuid, numeric); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE FUNCTION public.can_va_debit(p_va_id uuid, p_amount numeric) RETURNS boolean
    LANGUAGE plpgsql STABLE
    AS $$
DECLARE
    v_total_available DECIMAL(19,4);
BEGIN
    v_total_available := get_va_total_available(p_va_id);
    RETURN v_total_available >= p_amount;
END;
$$;


ALTER FUNCTION public.can_va_debit(p_va_id uuid, p_amount numeric) OWNER TO postgres;

--
-- TOC entry 6864 (class 0 OID 0)
-- Dependencies: 373
-- Name: FUNCTION can_va_debit(p_va_id uuid, p_amount numeric); Type: COMMENT; Schema: public; Owner: postgres
--

COMMENT ON FUNCTION public.can_va_debit(p_va_id uuid, p_amount numeric) IS 'Check if VA has sufficient funds (balance + credit) for debit';


--
-- TOC entry 387 (class 1255 OID 29889)
-- Name: clear_customer_context(); Type: FUNCTION; Schema: public; Owner: vam_user
--

CREATE FUNCTION public.clear_customer_context() RETURNS void
    LANGUAGE plpgsql
    AS $$
BEGIN
    PERFORM set_config('app.current_customer_id', '', FALSE);
END;
$$;


ALTER FUNCTION public.clear_customer_context() OWNER TO vam_user;

--
-- TOC entry 396 (class 1255 OID 51051)
-- Name: convert_currency(numeric, character varying, character varying, date, character varying); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE FUNCTION public.convert_currency(p_amount numeric, p_from_currency character varying, p_to_currency character varying, p_rate_date date DEFAULT CURRENT_DATE, p_rate_type character varying DEFAULT 'MID'::character varying) RETURNS numeric
    LANGUAGE plpgsql
    AS $$
DECLARE
    v_rate DECIMAL(19,8);
BEGIN
    IF p_from_currency = p_to_currency THEN
        RETURN p_amount;
    END IF;
    
    v_rate := get_fx_rate(p_from_currency, p_to_currency, p_rate_date, p_rate_type);
    
    IF v_rate IS NULL THEN
        RAISE EXCEPTION 'No FX rate found for % to % on %', p_from_currency, p_to_currency, p_rate_date;
    END IF;
    
    RETURN ROUND(p_amount * v_rate, 2);
END;
$$;


ALTER FUNCTION public.convert_currency(p_amount numeric, p_from_currency character varying, p_to_currency character varying, p_rate_date date, p_rate_type character varying) OWNER TO postgres;

--
-- TOC entry 6866 (class 0 OID 0)
-- Dependencies: 396
-- Name: FUNCTION convert_currency(p_amount numeric, p_from_currency character varying, p_to_currency character varying, p_rate_date date, p_rate_type character varying); Type: COMMENT; Schema: public; Owner: postgres
--

COMMENT ON FUNCTION public.convert_currency(p_amount numeric, p_from_currency character varying, p_to_currency character varying, p_rate_date date, p_rate_type character varying) IS 'Convert amount from one currency to another';


--
-- TOC entry 438 (class 1255 OID 30649)
-- Name: create_va_movement(uuid, uuid, character varying, numeric, character varying, character varying, character varying, uuid, character varying, character varying, character varying, date); Type: FUNCTION; Schema: public; Owner: vam_user
--

CREATE FUNCTION public.create_va_movement(p_virtual_account_id uuid, p_transaction_group_id uuid, p_movement_type character varying, p_amount numeric, p_currency character varying, p_counterparty_account character varying DEFAULT NULL::character varying, p_counterparty_name character varying DEFAULT NULL::character varying, p_counterparty_va_id uuid DEFAULT NULL::uuid, p_transaction_context character varying DEFAULT 'STANDARD'::character varying, p_description character varying DEFAULT NULL::character varying, p_e2e_reference character varying DEFAULT NULL::character varying, p_value_date date DEFAULT CURRENT_DATE) RETURNS uuid
    LANGUAGE plpgsql
    AS $$
DECLARE
    v_movement_id BIGINT;
    v_movement_uuid UUID;
    v_movement_ref VARCHAR(50);
    v_balance_before DECIMAL(18,2);
    v_balance_after DECIMAL(18,2);
    v_is_internal BOOLEAN;
BEGIN
    -- Get current balance
    SELECT COALESCE(current_balance, 0) INTO v_balance_before
    FROM virtual_accounts
    WHERE id = p_virtual_account_id
    FOR UPDATE;
    
    -- Calculate new balance
    IF p_movement_type = 'CREDIT' THEN
        v_balance_after := v_balance_before + p_amount;
    ELSE
        v_balance_after := v_balance_before - p_amount;
        
        -- Check sufficient balance (optional - can be disabled for overdraft)
        IF v_balance_after < 0 THEN
            RAISE EXCEPTION 'Insufficient balance. Available: %, Required: %', 
                v_balance_before, p_amount;
        END IF;
    END IF;
    
    -- Get next movement ID for this VA
    v_movement_id := get_next_movement_id(p_virtual_account_id);
    
    -- Generate movement reference
    v_movement_ref := 'MOV-' || EXTRACT(EPOCH FROM CURRENT_TIMESTAMP)::BIGINT || '-' || v_movement_id;
    
    -- Determine if internal
    v_is_internal := p_counterparty_va_id IS NOT NULL;
    
    -- Insert movement
    INSERT INTO va_movements (
        movement_id, movement_reference, virtual_account_id, transaction_group_id,
        movement_type, amount, currency_code,
        balance_before, balance_after,
        counterparty_account, counterparty_name, 
        is_internal, counterparty_va_id,
        transaction_context, movement_description,
        e2e_reference, value_date, status
    ) VALUES (
        v_movement_id, v_movement_ref, p_virtual_account_id, p_transaction_group_id,
        p_movement_type, p_amount, p_currency,
        v_balance_before, v_balance_after,
        p_counterparty_account, p_counterparty_name,
        v_is_internal, p_counterparty_va_id,
        p_transaction_context, p_description,
        p_e2e_reference, p_value_date, 'POSTED'
    )
    RETURNING id INTO v_movement_uuid;
    
    -- Update VA balance
    UPDATE virtual_accounts
    SET current_balance = v_balance_after,
        available_balance = v_balance_after - COALESCE(fund_hold, 0),
        balance_last_updated = CURRENT_TIMESTAMP,
        updated_at = CURRENT_TIMESTAMP
    WHERE id = p_virtual_account_id;
    
    -- Update transaction group movement count
    IF p_transaction_group_id IS NOT NULL THEN
        UPDATE transaction_groups
        SET actual_movements = actual_movements + 1,
            status = CASE 
                WHEN actual_movements + 1 >= expected_movements THEN 'COMPLETED'
                ELSE 'IN_PROGRESS'
            END,
            completed_at = CASE 
                WHEN actual_movements + 1 >= expected_movements THEN CURRENT_TIMESTAMP
                ELSE completed_at
            END,
            updated_at = CURRENT_TIMESTAMP
        WHERE id = p_transaction_group_id;
    END IF;
    
    RETURN v_movement_uuid;
END;
$$;


ALTER FUNCTION public.create_va_movement(p_virtual_account_id uuid, p_transaction_group_id uuid, p_movement_type character varying, p_amount numeric, p_currency character varying, p_counterparty_account character varying, p_counterparty_name character varying, p_counterparty_va_id uuid, p_transaction_context character varying, p_description character varying, p_e2e_reference character varying, p_value_date date) OWNER TO vam_user;

--
-- TOC entry 6868 (class 0 OID 0)
-- Dependencies: 438
-- Name: FUNCTION create_va_movement(p_virtual_account_id uuid, p_transaction_group_id uuid, p_movement_type character varying, p_amount numeric, p_currency character varying, p_counterparty_account character varying, p_counterparty_name character varying, p_counterparty_va_id uuid, p_transaction_context character varying, p_description character varying, p_e2e_reference character varying, p_value_date date); Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON FUNCTION public.create_va_movement(p_virtual_account_id uuid, p_transaction_group_id uuid, p_movement_type character varying, p_amount numeric, p_currency character varying, p_counterparty_account character varying, p_counterparty_name character varying, p_counterparty_va_id uuid, p_transaction_context character varying, p_description character varying, p_e2e_reference character varying, p_value_date date) IS 'Creates a single VA movement and updates balances';


--
-- TOC entry 411 (class 1255 OID 29887)
-- Name: current_customer_id(); Type: FUNCTION; Schema: public; Owner: vam_user
--

CREATE FUNCTION public.current_customer_id() RETURNS uuid
    LANGUAGE plpgsql STABLE
    AS $$
BEGIN
    RETURN NULLIF(current_setting('app.current_customer_id', TRUE), '')::UUID;
EXCEPTION
    WHEN OTHERS THEN
        RETURN NULL;
END;
$$;


ALTER FUNCTION public.current_customer_id() OWNER TO vam_user;

--
-- TOC entry 311 (class 1255 OID 51128)
-- Name: enable_ihb_for_entity(uuid, numeric, boolean, boolean, numeric, numeric); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE FUNCTION public.enable_ihb_for_entity(p_entity_id uuid, p_credit_limit numeric DEFAULT NULL::numeric, p_can_lend boolean DEFAULT false, p_can_borrow boolean DEFAULT true, p_lending_spread numeric DEFAULT 0.50, p_borrowing_spread numeric DEFAULT 0.75) RETURNS void
    LANGUAGE plpgsql
    AS $$
DECLARE
    v_functional_currency VARCHAR(3);
BEGIN
    -- Get entity's functional currency
    SELECT functional_currency INTO v_functional_currency
    FROM legal_entities WHERE id = p_entity_id;
    
    IF v_functional_currency IS NULL THEN
        RAISE EXCEPTION 'Entity not found: %', p_entity_id;
    END IF;
    
    -- Update entity with IHB settings
    UPDATE legal_entities
    SET 
        ihb_enabled = TRUE,
        ihb_credit_limit = p_credit_limit,
        ihb_available_limit = p_credit_limit,
        ihb_current_exposure = 0,
        can_lend = p_can_lend,
        can_borrow = p_can_borrow,
        lending_rate_spread = p_lending_spread,
        borrowing_rate_spread = p_borrowing_spread,
        ihb_currency = v_functional_currency,
        total_lent_out = 0,
        total_deposited = 0,
        net_ihb_position = 0,
        updated_at = CURRENT_TIMESTAMP
    WHERE id = p_entity_id;
    
    RAISE NOTICE 'IHB enabled for entity % with credit limit %', p_entity_id, p_credit_limit;
END;
$$;


ALTER FUNCTION public.enable_ihb_for_entity(p_entity_id uuid, p_credit_limit numeric, p_can_lend boolean, p_can_borrow boolean, p_lending_spread numeric, p_borrowing_spread numeric) OWNER TO postgres;

--
-- TOC entry 6869 (class 0 OID 0)
-- Dependencies: 311
-- Name: FUNCTION enable_ihb_for_entity(p_entity_id uuid, p_credit_limit numeric, p_can_lend boolean, p_can_borrow boolean, p_lending_spread numeric, p_borrowing_spread numeric); Type: COMMENT; Schema: public; Owner: postgres
--

COMMENT ON FUNCTION public.enable_ihb_for_entity(p_entity_id uuid, p_credit_limit numeric, p_can_lend boolean, p_can_borrow boolean, p_lending_spread numeric, p_borrowing_spread numeric) IS 'Enable In-House Banking for a legal entity with specified limits and permissions';


--
-- TOC entry 331 (class 1255 OID 50914)
-- Name: fn_get_or_create_currency_mirror(uuid, character varying, uuid); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE FUNCTION public.fn_get_or_create_currency_mirror(p_hierarchy_node_id uuid, p_currency_code character varying, p_program_id uuid) RETURNS uuid
    LANGUAGE plpgsql
    AS $$
DECLARE
    v_mirror_id UUID;
    v_mirror_code VARCHAR(100);
    v_node_code VARCHAR(50);
    v_node_name VARCHAR(100);
    v_node_path VARCHAR(500);
    v_node_level INTEGER;
    v_parent_node_id UUID;
    v_parent_mirror_id UUID;
    v_program_currency VARCHAR(3);
    v_corporate_id UUID;
    v_physical_account_id UUID;
BEGIN
    -- Check if Currency Mirror VA already exists
    SELECT id INTO v_mirror_id
    FROM virtual_accounts
    WHERE hierarchy_node_id = p_hierarchy_node_id
      AND account_category = 'CURRENCY_MIRROR'
      AND currency_code = p_currency_code;
    
    IF v_mirror_id IS NOT NULL THEN
        RETURN v_mirror_id;
    END IF;
    
    -- Get node details
    SELECT node_code, node_name, materialized_path, level_number, parent_id
    INTO v_node_code, v_node_name, v_node_path, v_node_level, v_parent_node_id
    FROM hierarchy_nodes
    WHERE id = p_hierarchy_node_id;
    
    IF v_node_code IS NULL THEN
        RAISE EXCEPTION 'Hierarchy node not found: %', p_hierarchy_node_id;
    END IF;
    
    -- Get program details
    SELECT currency_code, corporate_id, physical_account_id
    INTO v_program_currency, v_corporate_id, v_physical_account_id
    FROM unified_programs
    WHERE id = p_program_id;
    
    -- Find parent currency mirror if parent node exists
    IF v_parent_node_id IS NOT NULL THEN
        -- Recursively ensure parent mirror exists
        v_parent_mirror_id := fn_get_or_create_currency_mirror(
            v_parent_node_id,
            p_currency_code,
            p_program_id
        );
    END IF;
    
    -- Generate mirror code
    v_mirror_code := 'M-' || p_currency_code || '-' || LEFT(v_node_code, 30);
    
    -- Create Currency Mirror VA
    INSERT INTO virtual_accounts (
        id,
        va_number,
        va_name,
        program_id,
        corporate_id,
        physical_account_id,
        currency_code,
        account_type,
        account_category,
        hierarchy_node_id,
        hierarchy_path,
        hierarchy_path_va,
        hierarchy_level,
        parent_account_id,
        base_currency,
        mirror_balance,
        fx_rate,
        fx_rate_at,
        fx_rate_source,
        balance_in_base,
        current_balance,
        available_balance,
        held_balance,
        status,
        transaction_count,
        created_at,
        updated_at
    ) VALUES (
        gen_random_uuid(),
        v_mirror_code,
        p_currency_code || ' Mirror (' || v_node_name || ')',
        p_program_id,
        v_corporate_id,
        v_physical_account_id,
        p_currency_code,
        'VIRTUAL',
        'CURRENCY_MIRROR',
        p_hierarchy_node_id,
        v_node_path,
        v_node_path || '/M-' || p_currency_code,
        v_node_level,
        v_parent_mirror_id,
        v_program_currency,
        0,
        CASE WHEN p_currency_code = v_program_currency THEN 1.0 ELSE 1.0 END,
        CURRENT_TIMESTAMP,
        'SYSTEM',
        0,
        0,
        0,
        0,
        'ACTIVE',
        0,
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP
    ) RETURNING id INTO v_mirror_id;
    
    RETURN v_mirror_id;
END;
$$;


ALTER FUNCTION public.fn_get_or_create_currency_mirror(p_hierarchy_node_id uuid, p_currency_code character varying, p_program_id uuid) OWNER TO postgres;

--
-- TOC entry 6871 (class 0 OID 0)
-- Dependencies: 331
-- Name: FUNCTION fn_get_or_create_currency_mirror(p_hierarchy_node_id uuid, p_currency_code character varying, p_program_id uuid); Type: COMMENT; Schema: public; Owner: postgres
--

COMMENT ON FUNCTION public.fn_get_or_create_currency_mirror(p_hierarchy_node_id uuid, p_currency_code character varying, p_program_id uuid) IS 'Gets existing or creates new Currency Mirror VA for a hierarchy node and currency. Recursively creates parent mirrors.';


--
-- TOC entry 384 (class 1255 OID 50915)
-- Name: fn_propagate_mirror_balance(uuid, numeric); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE FUNCTION public.fn_propagate_mirror_balance(p_va_id uuid, p_delta numeric) RETURNS void
    LANGUAGE plpgsql
    AS $$
DECLARE
    v_parent_id UUID;
    v_current_id UUID;
    v_is_mirror BOOLEAN;
    v_new_balance DECIMAL(19,4);
    v_fx_rate DECIMAL(18,8);
BEGIN
    IF p_delta IS NULL OR p_delta = 0 THEN
        RETURN;
    END IF;
    
    -- Get parent account ID
    SELECT parent_account_id INTO v_parent_id
    FROM virtual_accounts
    WHERE id = p_va_id;
    
    -- Walk up the parent chain
    WHILE v_parent_id IS NOT NULL LOOP
        -- Check if parent is a Currency Mirror
        SELECT 
            account_category = 'CURRENCY_MIRROR',
            fx_rate
        INTO v_is_mirror, v_fx_rate
        FROM virtual_accounts
        WHERE id = v_parent_id;
        
        IF v_is_mirror THEN
            -- Update mirror balance
            UPDATE virtual_accounts
            SET 
                mirror_balance = COALESCE(mirror_balance, 0) + p_delta,
                current_balance = COALESCE(current_balance, 0) + p_delta,
                available_balance = COALESCE(available_balance, 0) + p_delta,
                balance_in_base = (COALESCE(mirror_balance, 0) + p_delta) * COALESCE(fx_rate, 1.0),
                updated_at = CURRENT_TIMESTAMP
            WHERE id = v_parent_id
            RETURNING parent_account_id INTO v_parent_id;
        ELSE
            -- Not a mirror, stop propagation
            EXIT;
        END IF;
    END LOOP;
END;
$$;


ALTER FUNCTION public.fn_propagate_mirror_balance(p_va_id uuid, p_delta numeric) OWNER TO postgres;

--
-- TOC entry 6873 (class 0 OID 0)
-- Dependencies: 384
-- Name: FUNCTION fn_propagate_mirror_balance(p_va_id uuid, p_delta numeric); Type: COMMENT; Schema: public; Owner: postgres
--

COMMENT ON FUNCTION public.fn_propagate_mirror_balance(p_va_id uuid, p_delta numeric) IS 'Propagates a balance change delta up the Currency Mirror chain via parent_account_id.';


--
-- TOC entry 343 (class 1255 OID 33800)
-- Name: generate_batch_reference(uuid); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE FUNCTION public.generate_batch_reference(p_corporate_id uuid) RETURNS character varying
    LANGUAGE plpgsql
    AS $$
DECLARE
    v_seq BIGINT;
    v_date VARCHAR(8);
BEGIN
    v_date := TO_CHAR(CURRENT_DATE, 'YYYYMMDD');
    
    SELECT COALESCE(MAX(
        CAST(SUBSTRING(batch_reference FROM 15) AS BIGINT)
    ), 0) + 1
    INTO v_seq
    FROM payment_batches
    WHERE batch_reference LIKE 'BATCH-' || v_date || '-%';
    
    RETURN 'BATCH-' || v_date || '-' || LPAD(v_seq::TEXT, 4, '0');
END;
$$;


ALTER FUNCTION public.generate_batch_reference(p_corporate_id uuid) OWNER TO postgres;

--
-- TOC entry 324 (class 1255 OID 33799)
-- Name: generate_payable_number(character varying, uuid); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE FUNCTION public.generate_payable_number(p_type character varying, p_corporate_id uuid) RETURNS character varying
    LANGUAGE plpgsql
    AS $$
DECLARE
    v_prefix VARCHAR(10);
    v_seq BIGINT;
    v_year VARCHAR(4);
BEGIN
    v_year := TO_CHAR(CURRENT_DATE, 'YYYY');
    
    v_prefix := CASE p_type
        WHEN 'INVOICE' THEN 'PAY'
        WHEN 'EXPENSE' THEN 'EXP'
        WHEN 'SALARY' THEN 'SAL'
        WHEN 'TAX' THEN 'TAX'
        WHEN 'UTILITY' THEN 'UTL'
        WHEN 'REFUND' THEN 'REF'
        ELSE 'PAY'
    END;
    
    SELECT COALESCE(MAX(
        CAST(SUBSTRING(payable_number FROM LENGTH(v_prefix) + 6) AS BIGINT)
    ), 0) + 1
    INTO v_seq
    FROM payables
    WHERE payable_number LIKE v_prefix || '-' || v_year || '-%';
    
    RETURN v_prefix || '-' || v_year || '-' || LPAD(v_seq::TEXT, 6, '0');
END;
$$;


ALTER FUNCTION public.generate_payable_number(p_type character varying, p_corporate_id uuid) OWNER TO postgres;

--
-- TOC entry 380 (class 1255 OID 33588)
-- Name: generate_receivable_number(character varying, uuid); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE FUNCTION public.generate_receivable_number(p_type character varying, p_corporate_id uuid) RETURNS character varying
    LANGUAGE plpgsql
    AS $$
DECLARE
    v_prefix VARCHAR(10);
    v_seq BIGINT;
    v_year VARCHAR(4);
BEGIN
    v_year := TO_CHAR(CURRENT_DATE, 'YYYY');
    
    v_prefix := CASE p_type
        WHEN 'INVOICE' THEN 'INV'
        WHEN 'ORDER' THEN 'ORD'
        WHEN 'SUBSCRIPTION' THEN 'SUB'
        WHEN 'INSTALLMENT' THEN 'INS'
        WHEN 'DEPOSIT' THEN 'DEP'
        ELSE 'RCV'
    END;
    
    SELECT COALESCE(MAX(
        CAST(SUBSTRING(receivable_number FROM LENGTH(v_prefix) + 6) AS BIGINT)
    ), 0) + 1
    INTO v_seq
    FROM receivables
    WHERE receivable_number LIKE v_prefix || '-' || v_year || '-%';
    
    RETURN v_prefix || '-' || v_year || '-' || LPAD(v_seq::TEXT, 6, '0');
END;
$$;


ALTER FUNCTION public.generate_receivable_number(p_type character varying, p_corporate_id uuid) OWNER TO postgres;

--
-- TOC entry 429 (class 1255 OID 34039)
-- Name: generate_recharge_reference(); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE FUNCTION public.generate_recharge_reference() RETURNS character varying
    LANGUAGE plpgsql
    AS $$
DECLARE
    v_seq BIGINT;
    v_date VARCHAR(8);
BEGIN
    v_date := TO_CHAR(CURRENT_DATE, 'YYYYMMDD');
    
    SELECT COALESCE(MAX(
        CAST(SUBSTRING(recharge_reference FROM 13) AS BIGINT)
    ), 0) + 1
    INTO v_seq
    FROM intercompany_recharges
    WHERE recharge_reference LIKE 'ICR-' || v_date || '-%';
    
    RETURN 'ICR-' || v_date || '-' || LPAD(v_seq::TEXT, 6, '0');
END;
$$;


ALTER FUNCTION public.generate_recharge_reference() OWNER TO postgres;

--
-- TOC entry 6878 (class 0 OID 0)
-- Dependencies: 429
-- Name: FUNCTION generate_recharge_reference(); Type: COMMENT; Schema: public; Owner: postgres
--

COMMENT ON FUNCTION public.generate_recharge_reference() IS 'Generate unique recharge reference';


--
-- TOC entry 408 (class 1255 OID 33327)
-- Name: generate_viban(character varying, character varying, character varying); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE FUNCTION public.generate_viban(p_country_code character varying, p_bank_code character varying, p_account_number character varying) RETURNS character varying
    LANGUAGE plpgsql
    AS $$
DECLARE
    v_bban VARCHAR;
    v_numeric_iban VARCHAR;
    v_check_digit INTEGER;
    v_char CHAR;
    v_code INTEGER;
BEGIN
    -- Construct BBAN (Basic Bank Account Number)
    v_bban := p_bank_code || p_account_number;
    
    -- Rearrange: BBAN + country code + '00'
    v_numeric_iban := v_bban || p_country_code || '00';
    
    -- Convert letters to numbers (A=10, B=11, etc.)
    FOR i IN 1..LENGTH(v_numeric_iban) LOOP
        v_char := SUBSTRING(v_numeric_iban FROM i FOR 1);
        IF v_char ~ '[A-Z]' THEN
            v_code := ASCII(v_char) - ASCII('A') + 10;
            v_numeric_iban := OVERLAY(v_numeric_iban PLACING v_code::VARCHAR FROM i FOR 1);
        END IF;
    END LOOP;
    
    -- Calculate check digit: 98 - (numeric_iban MOD 97)
    v_check_digit := 98 - (v_numeric_iban::NUMERIC % 97)::INTEGER;
    
    -- Return formatted VIBAN
    RETURN p_country_code || LPAD(v_check_digit::VARCHAR, 2, '0') || v_bban;
END;
$$;


ALTER FUNCTION public.generate_viban(p_country_code character varying, p_bank_code character varying, p_account_number character varying) OWNER TO postgres;

--
-- TOC entry 370 (class 1255 OID 50597)
-- Name: get_entity_available_limit(uuid); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE FUNCTION public.get_entity_available_limit(p_entity_id uuid) RETURNS numeric
    LANGUAGE plpgsql STABLE
    AS $$
DECLARE
    v_limit DECIMAL(19,4);
    v_utilized DECIMAL(19,4);
BEGIN
    SELECT internal_credit_limit, internal_limit_utilized
    INTO v_limit, v_utilized
    FROM legal_entities
    WHERE id = p_entity_id;
    
    IF v_limit IS NULL THEN
        RETURN 0;
    END IF;
    
    RETURN v_limit - COALESCE(v_utilized, 0);
END;
$$;


ALTER FUNCTION public.get_entity_available_limit(p_entity_id uuid) OWNER TO postgres;

--
-- TOC entry 6881 (class 0 OID 0)
-- Dependencies: 370
-- Name: FUNCTION get_entity_available_limit(p_entity_id uuid); Type: COMMENT; Schema: public; Owner: postgres
--

COMMENT ON FUNCTION public.get_entity_available_limit(p_entity_id uuid) IS 'Get available internal credit limit for entity';


--
-- TOC entry 374 (class 1255 OID 29795)
-- Name: get_fx_rate(character varying, character varying, date, character varying); Type: FUNCTION; Schema: public; Owner: vam_user
--

CREATE FUNCTION public.get_fx_rate(p_from_currency character varying, p_to_currency character varying, p_rate_date date DEFAULT CURRENT_DATE, p_rate_type character varying DEFAULT 'MID'::character varying) RETURNS numeric
    LANGUAGE plpgsql
    AS $$
DECLARE
    v_rate DECIMAL(19,8);
BEGIN
    -- Same currency = 1.0
    IF p_from_currency = p_to_currency THEN
        RETURN 1.0;
    END IF;
    
    -- Try direct rate
    SELECT 
        CASE p_rate_type
            WHEN 'BID' THEN COALESCE(bid_rate, rate)
            WHEN 'ASK' THEN COALESCE(ask_rate, rate)
            ELSE rate
        END INTO v_rate
    FROM fx_rates
    WHERE from_currency = p_from_currency
      AND to_currency = p_to_currency
      AND rate_date <= p_rate_date
      AND is_active = true
    ORDER BY rate_date DESC
    LIMIT 1;
    
    IF v_rate IS NOT NULL THEN
        RETURN v_rate;
    END IF;
    
    -- Try inverse rate
    SELECT 
        CASE p_rate_type
            WHEN 'BID' THEN 1.0 / COALESCE(ask_rate, rate)  -- Inverse of ASK for BID
            WHEN 'ASK' THEN 1.0 / COALESCE(bid_rate, rate)  -- Inverse of BID for ASK
            ELSE 1.0 / rate
        END INTO v_rate
    FROM fx_rates
    WHERE from_currency = p_to_currency
      AND to_currency = p_from_currency
      AND rate_date <= p_rate_date
      AND is_active = true
    ORDER BY rate_date DESC
    LIMIT 1;
    
    IF v_rate IS NOT NULL THEN
        RETURN v_rate;
    END IF;
    
    -- Try cross-rate via USD
    IF p_from_currency != 'USD' AND p_to_currency != 'USD' THEN
        DECLARE
            v_from_usd DECIMAL(19,8);
            v_usd_to DECIMAL(19,8);
        BEGIN
            v_from_usd := get_fx_rate(p_from_currency, 'USD', p_rate_date, p_rate_type);
            v_usd_to := get_fx_rate('USD', p_to_currency, p_rate_date, p_rate_type);
            
            IF v_from_usd IS NOT NULL AND v_usd_to IS NOT NULL THEN
                RETURN v_from_usd * v_usd_to;
            END IF;
        END;
    END IF;
    
    -- No rate found
    RETURN NULL;
END;
$$;


ALTER FUNCTION public.get_fx_rate(p_from_currency character varying, p_to_currency character varying, p_rate_date date, p_rate_type character varying) OWNER TO vam_user;

--
-- TOC entry 6883 (class 0 OID 0)
-- Dependencies: 374
-- Name: FUNCTION get_fx_rate(p_from_currency character varying, p_to_currency character varying, p_rate_date date, p_rate_type character varying); Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON FUNCTION public.get_fx_rate(p_from_currency character varying, p_to_currency character varying, p_rate_date date, p_rate_type character varying) IS 'Get FX rate with fallback to inverse and cross-rates';


--
-- TOC entry 416 (class 1255 OID 50596)
-- Name: get_legal_entity_ancestors(uuid); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE FUNCTION public.get_legal_entity_ancestors(p_entity_id uuid) RETURNS TABLE(entity_id uuid, entity_code character varying, entity_name character varying, hierarchy_level integer, hierarchy_path character varying)
    LANGUAGE plpgsql STABLE
    AS $$
BEGIN
    RETURN QUERY
    WITH RECURSIVE ancestors AS (
        -- Base case: the entity itself
        SELECT le.id, le.entity_code, le.entity_name, le.hierarchy_level, 
               le.hierarchy_path, le.parent_entity_id
        FROM legal_entities le
        WHERE le.id = p_entity_id
        
        UNION ALL
        
        -- Recursive case: parents
        SELECT le.id, le.entity_code, le.entity_name, le.hierarchy_level, 
               le.hierarchy_path, le.parent_entity_id
        FROM legal_entities le
        INNER JOIN ancestors a ON le.id = a.parent_entity_id
    )
    SELECT a.id, a.entity_code, a.entity_name, a.hierarchy_level, a.hierarchy_path
    FROM ancestors a
    ORDER BY a.hierarchy_level;
END;
$$;


ALTER FUNCTION public.get_legal_entity_ancestors(p_entity_id uuid) OWNER TO postgres;

--
-- TOC entry 6884 (class 0 OID 0)
-- Dependencies: 416
-- Name: FUNCTION get_legal_entity_ancestors(p_entity_id uuid); Type: COMMENT; Schema: public; Owner: postgres
--

COMMENT ON FUNCTION public.get_legal_entity_ancestors(p_entity_id uuid) IS 'Get all ancestors of a legal entity (recursive)';


--
-- TOC entry 334 (class 1255 OID 50595)
-- Name: get_legal_entity_descendants(uuid); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE FUNCTION public.get_legal_entity_descendants(p_entity_id uuid) RETURNS TABLE(entity_id uuid, entity_code character varying, entity_name character varying, hierarchy_level integer, hierarchy_path character varying)
    LANGUAGE plpgsql STABLE
    AS $$
BEGIN
    RETURN QUERY
    WITH RECURSIVE descendants AS (
        -- Base case: the entity itself
        SELECT le.id, le.entity_code, le.entity_name, le.hierarchy_level, le.hierarchy_path
        FROM legal_entities le
        WHERE le.id = p_entity_id
        
        UNION ALL
        
        -- Recursive case: children
        SELECT le.id, le.entity_code, le.entity_name, le.hierarchy_level, le.hierarchy_path
        FROM legal_entities le
        INNER JOIN descendants d ON le.parent_entity_id = d.id
    )
    SELECT d.id, d.entity_code, d.entity_name, d.hierarchy_level, d.hierarchy_path
    FROM descendants d
    ORDER BY d.hierarchy_path;
END;
$$;


ALTER FUNCTION public.get_legal_entity_descendants(p_entity_id uuid) OWNER TO postgres;

--
-- TOC entry 6886 (class 0 OID 0)
-- Dependencies: 334
-- Name: FUNCTION get_legal_entity_descendants(p_entity_id uuid); Type: COMMENT; Schema: public; Owner: postgres
--

COMMENT ON FUNCTION public.get_legal_entity_descendants(p_entity_id uuid) IS 'Get all descendants of a legal entity (recursive)';


--
-- TOC entry 321 (class 1255 OID 30648)
-- Name: get_next_movement_id(uuid); Type: FUNCTION; Schema: public; Owner: vam_user
--

CREATE FUNCTION public.get_next_movement_id(p_virtual_account_id uuid) RETURNS bigint
    LANGUAGE plpgsql
    AS $$
DECLARE
    v_next_id BIGINT;
BEGIN
    SELECT COALESCE(MAX(movement_id), 0) + 1 INTO v_next_id
    FROM va_movements
    WHERE virtual_account_id = p_virtual_account_id;
    
    RETURN v_next_id;
END;
$$;


ALTER FUNCTION public.get_next_movement_id(p_virtual_account_id uuid) OWNER TO vam_user;

--
-- TOC entry 358 (class 1255 OID 33150)
-- Name: get_node_ancestors(uuid); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE FUNCTION public.get_node_ancestors(p_node_id uuid) RETURNS TABLE(id uuid, node_code character varying, node_name character varying, level_number integer, materialized_path character varying)
    LANGUAGE plpgsql
    AS $$
DECLARE
    v_path VARCHAR;
    v_program_id UUID;
BEGIN
    -- Get the node's path and program
    SELECT h.materialized_path, h.program_id 
    INTO v_path, v_program_id
    FROM hierarchy_nodes h 
    WHERE h.id = p_node_id;
    
    IF v_path IS NULL THEN
        RETURN;
    END IF;
    
    -- Return all ancestors
    RETURN QUERY
    SELECT h.id, h.node_code, h.node_name, h.level_number, h.materialized_path
    FROM hierarchy_nodes h
    WHERE h.program_id = v_program_id
      AND v_path LIKE h.materialized_path || '/%'
    ORDER BY h.level_number;
END;
$$;


ALTER FUNCTION public.get_node_ancestors(p_node_id uuid) OWNER TO postgres;

--
-- TOC entry 322 (class 1255 OID 33151)
-- Name: get_node_descendants(uuid); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE FUNCTION public.get_node_descendants(p_node_id uuid) RETURNS TABLE(id uuid, node_code character varying, node_name character varying, level_number integer, materialized_path character varying, aggregated_balance numeric)
    LANGUAGE plpgsql
    AS $$
DECLARE
    v_path VARCHAR;
    v_program_id UUID;
BEGIN
    -- Get the node's path and program
    SELECT h.materialized_path, h.program_id 
    INTO v_path, v_program_id
    FROM hierarchy_nodes h 
    WHERE h.id = p_node_id;
    
    IF v_path IS NULL THEN
        RETURN;
    END IF;
    
    -- Return all descendants
    RETURN QUERY
    SELECT h.id, h.node_code, h.node_name, h.level_number, h.materialized_path, h.aggregated_balance
    FROM hierarchy_nodes h
    WHERE h.program_id = v_program_id
      AND h.materialized_path LIKE v_path || '/%'
    ORDER BY h.materialized_path;
END;
$$;


ALTER FUNCTION public.get_node_descendants(p_node_id uuid) OWNER TO postgres;

--
-- TOC entry 316 (class 1255 OID 50828)
-- Name: get_va_total_available(uuid); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE FUNCTION public.get_va_total_available(p_va_id uuid) RETURNS numeric
    LANGUAGE plpgsql STABLE
    AS $$
DECLARE
    v_balance DECIMAL(19,4);
    v_external_available DECIMAL(19,4) := 0;
    v_internal_available DECIMAL(19,4) := 0;
BEGIN
    -- Get VA balance
    SELECT COALESCE(available_balance, 0) INTO v_balance
    FROM virtual_accounts WHERE id = p_va_id;
    
    -- Get external limit available
    SELECT COALESCE(cl.available_amount, 0) INTO v_external_available
    FROM virtual_accounts va
    JOIN credit_limits cl ON va.external_limit_id = cl.id
    WHERE va.id = p_va_id AND cl.status = 'ACTIVE';
    
    -- Get internal limit available
    SELECT COALESCE(cl.available_amount, 0) INTO v_internal_available
    FROM virtual_accounts va
    JOIN credit_limits cl ON va.internal_limit_id = cl.id
    WHERE va.id = p_va_id AND cl.status = 'ACTIVE';
    
    RETURN v_balance + v_external_available + v_internal_available;
END;
$$;


ALTER FUNCTION public.get_va_total_available(p_va_id uuid) OWNER TO postgres;

--
-- TOC entry 6890 (class 0 OID 0)
-- Dependencies: 316
-- Name: FUNCTION get_va_total_available(p_va_id uuid); Type: COMMENT; Schema: public; Owner: postgres
--

COMMENT ON FUNCTION public.get_va_total_available(p_va_id uuid) IS 'Get total available balance including credit limits';


--
-- TOC entry 428 (class 1255 OID 33329)
-- Name: lookup_viban_for_routing(character varying); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE FUNCTION public.lookup_viban_for_routing(p_viban character varying) RETURNS TABLE(viban_id uuid, virtual_account_id uuid, program_id uuid, hierarchy_node_id uuid, va_number character varying, status character varying, reference_type character varying, reference_id character varying, expected_amount numeric, currency_code character varying, is_valid boolean, validation_message character varying)
    LANGUAGE plpgsql
    AS $$
BEGIN
    RETURN QUERY
    SELECT 
        v.id AS viban_id,
        v.virtual_account_id,
        v.program_id,
        v.hierarchy_node_id,
        va.va_number,
        v.status,
        v.reference_type,
        v.reference_id,
        v.expected_amount,
        v.currency_code,
        CASE 
            WHEN v.id IS NULL THEN FALSE
            WHEN v.status != 'ACTIVE' AND v.status != 'PARTIAL' THEN FALSE
            WHEN v.valid_until IS NOT NULL AND v.valid_until < CURRENT_TIMESTAMP THEN FALSE
            WHEN va.status != 'ACTIVE' THEN FALSE
            ELSE TRUE
        END AS is_valid,
        CASE 
            WHEN v.id IS NULL THEN 'VIBAN not found'
            WHEN v.status = 'EXPIRED' THEN 'VIBAN expired'
            WHEN v.status = 'CANCELLED' THEN 'VIBAN cancelled'
            WHEN v.status = 'PAID' THEN 'VIBAN already paid (single use)'
            WHEN v.valid_until IS NOT NULL AND v.valid_until < CURRENT_TIMESTAMP THEN 'VIBAN validity period ended'
            WHEN va.status != 'ACTIVE' THEN 'Virtual account not active'
            ELSE 'Valid'
        END AS validation_message
    FROM vibans v
    LEFT JOIN virtual_accounts va ON v.virtual_account_id = va.id
    WHERE v.viban = p_viban;
END;
$$;


ALTER FUNCTION public.lookup_viban_for_routing(p_viban character varying) OWNER TO postgres;

--
-- TOC entry 366 (class 1255 OID 29891)
-- Name: manual_match_reconciliation(uuid, character varying, character varying, text); Type: FUNCTION; Schema: public; Owner: vam_user
--

CREATE FUNCTION public.manual_match_reconciliation(p_reconciliation_id uuid, p_external_reference character varying, p_matched_by character varying, p_match_notes text DEFAULT NULL::text) RETURNS boolean
    LANGUAGE plpgsql
    AS $$
DECLARE
    v_old_status VARCHAR(20);
BEGIN
    -- Get current status
    SELECT match_status INTO v_old_status
    FROM reconciliation_records
    WHERE id = p_reconciliation_id;
    
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Reconciliation record not found: %', p_reconciliation_id;
    END IF;
    
    -- Update record
    UPDATE reconciliation_records
    SET 
        match_status = 'MANUAL_MATCH',
        match_method = 'MANUAL',
        match_confidence = 100,
        external_reference = p_external_reference,
        matched_by = p_matched_by,
        matched_at = CURRENT_TIMESTAMP,
        match_notes = p_match_notes,
        updated_at = CURRENT_TIMESTAMP
    WHERE id = p_reconciliation_id;
    
    -- Create audit record
    INSERT INTO reconciliation_audit (
        reconciliation_id,
        action,
        previous_status,
        new_status,
        change_details,
        change_reason,
        performed_by
    ) VALUES (
        p_reconciliation_id,
        'MATCHED',
        v_old_status,
        'MANUAL_MATCH',
        jsonb_build_object('external_reference', p_external_reference),
        p_match_notes,
        p_matched_by
    );
    
    RETURN TRUE;
END;
$$;


ALTER FUNCTION public.manual_match_reconciliation(p_reconciliation_id uuid, p_external_reference character varying, p_matched_by character varying, p_match_notes text) OWNER TO vam_user;

--
-- TOC entry 332 (class 1255 OID 30232)
-- Name: process_ecommerce_payment(character varying, numeric, character varying, character varying, character varying, character varying); Type: FUNCTION; Schema: public; Owner: vam_user
--

CREATE FUNCTION public.process_ecommerce_payment(p_iban character varying, p_amount numeric, p_currency character varying, p_payer_name character varying, p_payer_account character varying, p_reference character varying) RETURNS TABLE(status character varying, match_status character varying, variance numeric, auto_closed boolean)
    LANGUAGE plpgsql
    AS $$
DECLARE
    v_ecom RECORD;
    v_merchant RECORD;
    v_payment_id UUID;
    v_match_status VARCHAR(20);
    v_variance DECIMAL(18,2);
    v_should_close BOOLEAN := FALSE;
BEGIN
    -- Find e-commerce IBAN
    SELECT ei.*, em.auto_close_on_payment, em.auto_close_on_exact_amount,
           em.allow_overpayment, em.amount_tolerance_percentage, em.amount_tolerance_absolute
    INTO v_ecom
    FROM ecommerce_virtual_ibans ei
    JOIN ecommerce_merchants em ON ei.merchant_id = em.id
    WHERE ei.virtual_iban = p_iban AND ei.status = 'ACTIVE';
    
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Active e-commerce IBAN not found: %', p_iban;
    END IF;
    
    -- Calculate variance
    v_variance := p_amount - v_ecom.expected_amount;
    
    -- Determine match status
    IF ABS(v_variance) <= GREATEST(
        v_ecom.expected_amount * COALESCE(v_ecom.amount_tolerance_percentage, 0) / 100,
        COALESCE(v_ecom.amount_tolerance_absolute, 0)
    ) THEN
        v_match_status := 'EXACT';
        v_should_close := v_ecom.auto_close_on_exact_amount;
    ELSIF v_variance > 0 THEN
        v_match_status := 'OVERPAID';
        v_should_close := v_ecom.auto_close_on_payment AND v_ecom.allow_overpayment;
    ELSE
        v_match_status := 'UNDERPAID';
        v_should_close := FALSE; -- Don't auto-close underpayments
    END IF;
    
    -- Record payment
    INSERT INTO ecommerce_payments (
        ecommerce_iban_id, payment_reference, amount, currency_code,
        payer_name, payer_account, match_status, amount_variance, status
    ) VALUES (
        v_ecom.id, p_reference, p_amount, p_currency,
        p_payer_name, p_payer_account, v_match_status, v_variance, 'MATCHED'
    ) RETURNING id INTO v_payment_id;
    
    -- Update e-commerce IBAN
    UPDATE ecommerce_virtual_ibans
    SET received_amount = received_amount + p_amount,
        received_count = received_count + 1,
        last_payment_at = CURRENT_TIMESTAMP,
        payment_status = v_match_status,
        status = CASE WHEN v_should_close THEN 'PAID' ELSE status END,
        auto_closed = v_should_close,
        closed_at = CASE WHEN v_should_close THEN CURRENT_TIMESTAMP ELSE NULL END,
        closure_reason = CASE WHEN v_should_close THEN 'PAID' ELSE NULL END,
        updated_at = CURRENT_TIMESTAMP
    WHERE id = v_ecom.id;
    
    -- Close VA if needed
    IF v_should_close THEN
        UPDATE virtual_accounts SET status = 0, closed_on = CURRENT_DATE
        WHERE id = v_ecom.virtual_account_id;
    END IF;
    
    RETURN QUERY SELECT 'SUCCESS'::VARCHAR(20), v_match_status, v_variance, v_should_close;
END;
$$;


ALTER FUNCTION public.process_ecommerce_payment(p_iban character varying, p_amount numeric, p_currency character varying, p_payer_name character varying, p_payer_account character varying, p_reference character varying) OWNER TO vam_user;

--
-- TOC entry 327 (class 1255 OID 30653)
-- Name: record_ic_movement(uuid, uuid, uuid, numeric, character varying, character varying, character varying, uuid, text); Type: FUNCTION; Schema: public; Owner: vam_user
--

CREATE FUNCTION public.record_ic_movement(p_ihb_id uuid, p_from_entity_id uuid, p_to_entity_id uuid, p_amount numeric, p_currency character varying, p_movement_type character varying, p_reference_type character varying, p_reference_id uuid, p_description text DEFAULT NULL::text) RETURNS uuid
    LANGUAGE plpgsql
    AS $$
DECLARE
    v_position_id UUID;
    v_entity_a_id UUID;
    v_entity_b_id UUID;
    v_position_before DECIMAL(18,2);
    v_position_after DECIMAL(18,2);
    v_adjustment DECIMAL(18,2);
    v_movement_id UUID;
BEGIN
    -- Determine entity order (a < b)
    IF p_from_entity_id < p_to_entity_id THEN
        v_entity_a_id := p_from_entity_id;
        v_entity_b_id := p_to_entity_id;
        v_adjustment := p_amount; -- A owes B more (positive)
    ELSE
        v_entity_a_id := p_to_entity_id;
        v_entity_b_id := p_from_entity_id;
        v_adjustment := -p_amount; -- B owes A more (negative from A's perspective)
    END IF;
    
    -- Get or create position
    SELECT id, net_position INTO v_position_id, v_position_before
    FROM ic_positions
    WHERE ihb_id = p_ihb_id 
      AND entity_a_id = v_entity_a_id 
      AND entity_b_id = v_entity_b_id
      AND currency_code = p_currency
    FOR UPDATE;
    
    IF v_position_id IS NULL THEN
        -- Create new position
        INSERT INTO ic_positions (ihb_id, entity_a_id, entity_b_id, currency_code, net_position)
        VALUES (p_ihb_id, v_entity_a_id, v_entity_b_id, p_currency, 0)
        RETURNING id, net_position INTO v_position_id, v_position_before;
    END IF;
    
    -- Calculate new position
    v_position_after := v_position_before + v_adjustment;
    
    -- Update position
    UPDATE ic_positions
    SET net_position = v_position_after,
        gross_a_to_b = gross_a_to_b + CASE WHEN v_adjustment > 0 THEN p_amount ELSE 0 END,
        gross_b_to_a = gross_b_to_a + CASE WHEN v_adjustment < 0 THEN p_amount ELSE 0 END,
        last_movement_at = CURRENT_TIMESTAMP,
        updated_at = CURRENT_TIMESTAMP
    WHERE id = v_position_id;
    
    -- Record movement
    INSERT INTO ic_position_movements (
        position_id, movement_type, from_entity_id, to_entity_id,
        amount, currency_code, position_before, position_after,
        reference_type, reference_id, description, effective_date
    ) VALUES (
        v_position_id, p_movement_type, p_from_entity_id, p_to_entity_id,
        p_amount, p_currency, v_position_before, v_position_after,
        p_reference_type, p_reference_id, p_description, CURRENT_DATE
    )
    RETURNING id INTO v_movement_id;
    
    RETURN v_movement_id;
END;
$$;


ALTER FUNCTION public.record_ic_movement(p_ihb_id uuid, p_from_entity_id uuid, p_to_entity_id uuid, p_amount numeric, p_currency character varying, p_movement_type character varying, p_reference_type character varying, p_reference_id uuid, p_description text) OWNER TO vam_user;

--
-- TOC entry 6906 (class 0 OID 0)
-- Dependencies: 327
-- Name: FUNCTION record_ic_movement(p_ihb_id uuid, p_from_entity_id uuid, p_to_entity_id uuid, p_amount numeric, p_currency character varying, p_movement_type character varying, p_reference_type character varying, p_reference_id uuid, p_description text); Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON FUNCTION public.record_ic_movement(p_ihb_id uuid, p_from_entity_id uuid, p_to_entity_id uuid, p_amount numeric, p_currency character varying, p_movement_type character varying, p_reference_type character varying, p_reference_id uuid, p_description text) IS 'Records an IC position movement and updates the running position';


--
-- TOC entry 346 (class 1255 OID 29888)
-- Name: set_customer_context(uuid); Type: FUNCTION; Schema: public; Owner: vam_user
--

CREATE FUNCTION public.set_customer_context(p_customer_id uuid) RETURNS void
    LANGUAGE plpgsql
    AS $$
BEGIN
    PERFORM set_config('app.current_customer_id', p_customer_id::TEXT, FALSE);
END;
$$;


ALTER FUNCTION public.set_customer_context(p_customer_id uuid) OWNER TO vam_user;

--
-- TOC entry 342 (class 1255 OID 51132)
-- Name: trg_update_ihb_position_on_deposit_change(); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE FUNCTION public.trg_update_ihb_position_on_deposit_change() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'INSERT' OR TG_OP = 'UPDATE' THEN
        IF NEW.depositor_legal_entity_id IS NOT NULL THEN
            PERFORM update_entity_ihb_position(NEW.depositor_legal_entity_id);
        END IF;
    END IF;
    
    IF TG_OP = 'DELETE' OR TG_OP = 'UPDATE' THEN
        IF OLD.depositor_legal_entity_id IS NOT NULL AND 
           (TG_OP = 'DELETE' OR OLD.depositor_legal_entity_id != NEW.depositor_legal_entity_id) THEN
            PERFORM update_entity_ihb_position(OLD.depositor_legal_entity_id);
        END IF;
    END IF;
    
    RETURN COALESCE(NEW, OLD);
END;
$$;


ALTER FUNCTION public.trg_update_ihb_position_on_deposit_change() OWNER TO postgres;

--
-- TOC entry 407 (class 1255 OID 51130)
-- Name: trg_update_ihb_position_on_loan_change(); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE FUNCTION public.trg_update_ihb_position_on_loan_change() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'INSERT' OR TG_OP = 'UPDATE' THEN
        IF NEW.lender_legal_entity_id IS NOT NULL THEN
            PERFORM update_entity_ihb_position(NEW.lender_legal_entity_id);
        END IF;
        IF NEW.borrower_legal_entity_id IS NOT NULL THEN
            PERFORM update_entity_ihb_position(NEW.borrower_legal_entity_id);
        END IF;
    END IF;
    
    IF TG_OP = 'DELETE' OR TG_OP = 'UPDATE' THEN
        IF OLD.lender_legal_entity_id IS NOT NULL AND 
           (TG_OP = 'DELETE' OR OLD.lender_legal_entity_id != NEW.lender_legal_entity_id) THEN
            PERFORM update_entity_ihb_position(OLD.lender_legal_entity_id);
        END IF;
        IF OLD.borrower_legal_entity_id IS NOT NULL AND 
           (TG_OP = 'DELETE' OR OLD.borrower_legal_entity_id != NEW.borrower_legal_entity_id) THEN
            PERFORM update_entity_ihb_position(OLD.borrower_legal_entity_id);
        END IF;
    END IF;
    
    RETURN COALESCE(NEW, OLD);
END;
$$;


ALTER FUNCTION public.trg_update_ihb_position_on_loan_change() OWNER TO postgres;

--
-- TOC entry 318 (class 1255 OID 51129)
-- Name: update_entity_ihb_position(uuid); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE FUNCTION public.update_entity_ihb_position(p_entity_id uuid) RETURNS void
    LANGUAGE plpgsql
    AS $$
DECLARE
    v_total_lent NUMERIC(19,4) := 0;
    v_total_borrowed NUMERIC(19,4) := 0;
    v_total_deposited NUMERIC(19,4) := 0;
BEGIN
    -- Calculate total lent
    SELECT COALESCE(SUM(outstanding_amount), 0) INTO v_total_lent
    FROM ihb_loans
    WHERE lender_legal_entity_id = p_entity_id AND status = 'ACTIVE';
    
    -- Calculate total borrowed
    SELECT COALESCE(SUM(outstanding_amount), 0) INTO v_total_borrowed
    FROM ihb_loans
    WHERE borrower_legal_entity_id = p_entity_id AND status = 'ACTIVE';
    
    -- Calculate total deposited
    SELECT COALESCE(SUM(current_balance), 0) INTO v_total_deposited
    FROM ihb_deposits
    WHERE depositor_legal_entity_id = p_entity_id AND status = 'ACTIVE';
    
    -- Update entity
    UPDATE legal_entities
    SET 
        total_lent_out = v_total_lent,
        ihb_current_exposure = v_total_borrowed,
        ihb_available_limit = COALESCE(ihb_credit_limit, 0) - v_total_borrowed,
        total_deposited = v_total_deposited,
        net_ihb_position = v_total_lent + v_total_deposited - v_total_borrowed,
        updated_at = CURRENT_TIMESTAMP
    WHERE id = p_entity_id;
END;
$$;


ALTER FUNCTION public.update_entity_ihb_position(p_entity_id uuid) OWNER TO postgres;

--
-- TOC entry 6920 (class 0 OID 0)
-- Dependencies: 318
-- Name: FUNCTION update_entity_ihb_position(p_entity_id uuid); Type: COMMENT; Schema: public; Owner: postgres
--

COMMENT ON FUNCTION public.update_entity_ihb_position(p_entity_id uuid) IS 'Recalculate and update IHB position metrics for a legal entity';


--
-- TOC entry 431 (class 1255 OID 51049)
-- Name: update_fx_rates_updated_at(); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE FUNCTION public.update_fx_rates_updated_at() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$;


ALTER FUNCTION public.update_fx_rates_updated_at() OWNER TO postgres;

--
-- TOC entry 329 (class 1255 OID 33145)
-- Name: update_hierarchy_timestamp(); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE FUNCTION public.update_hierarchy_timestamp() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;


ALTER FUNCTION public.update_hierarchy_timestamp() OWNER TO postgres;

--
-- TOC entry 312 (class 1255 OID 33148)
-- Name: update_parent_child_count(); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE FUNCTION public.update_parent_child_count() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.parent_id IS NOT NULL THEN
            UPDATE hierarchy_nodes 
            SET child_count = child_count + 1,
                is_leaf = false,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = NEW.parent_id;
        END IF;
        RETURN NEW;
    ELSIF TG_OP = 'DELETE' THEN
        IF OLD.parent_id IS NOT NULL THEN
            UPDATE hierarchy_nodes 
            SET child_count = GREATEST(child_count - 1, 0),
                updated_at = CURRENT_TIMESTAMP
            WHERE id = OLD.parent_id;
            
            -- Check if parent should become leaf
            UPDATE hierarchy_nodes 
            SET is_leaf = true
            WHERE id = OLD.parent_id 
              AND child_count = 0 
              AND node_type != 'MASTER';
        END IF;
        RETURN OLD;
    END IF;
    RETURN NULL;
END;
$$;


ALTER FUNCTION public.update_parent_child_count() OWNER TO postgres;

--
-- TOC entry 398 (class 1255 OID 51202)
-- Name: update_party_ic_exposure(uuid, uuid); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE FUNCTION public.update_party_ic_exposure(p_party_id uuid, p_corporate_id uuid) RETURNS void
    LANGUAGE plpgsql
    AS $$
DECLARE
    v_total_payables NUMERIC(19, 4) := 0;
    v_total_receivables NUMERIC(19, 4) := 0;
    v_net_exposure NUMERIC(19, 4);
BEGIN
    -- This will be enhanced in Phase 2/3 when Payables/Receivables are linked
    -- For now, just set the infrastructure
    
    -- Calculate total outstanding payables to this party
    -- (Will be implemented when Payable entity has party_id)
    
    -- Calculate total outstanding receivables from this party
    -- (Will be implemented when Receivable entity has party_id)
    
    v_net_exposure := v_total_payables - v_total_receivables;
    
    -- Update the party's IC exposure
    UPDATE parties 
    SET ic_current_exposure = v_net_exposure,
        updated_at = NOW()
    WHERE id = p_party_id 
      AND is_intercompany = TRUE;
      
    RAISE NOTICE 'Updated IC exposure for party %: %', p_party_id, v_net_exposure;
END;
$$;


ALTER FUNCTION public.update_party_ic_exposure(p_party_id uuid, p_corporate_id uuid) OWNER TO postgres;

--
-- TOC entry 6925 (class 0 OID 0)
-- Dependencies: 398
-- Name: FUNCTION update_party_ic_exposure(p_party_id uuid, p_corporate_id uuid); Type: COMMENT; Schema: public; Owner: postgres
--

COMMENT ON FUNCTION public.update_party_ic_exposure(p_party_id uuid, p_corporate_id uuid) IS 'Updates intercompany exposure for a party based on outstanding payables/receivables.';


--
-- TOC entry 395 (class 1255 OID 33797)
-- Name: update_payable_outstanding(); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE FUNCTION public.update_payable_outstanding() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    NEW.outstanding_amount := NEW.net_amount - COALESCE(NEW.paid_amount, 0);
    
    -- Auto-update payment status
    IF NEW.paid_amount >= NEW.net_amount THEN
        NEW.payment_status := 'PAID';
        IF NEW.status NOT IN ('CANCELLED', 'REJECTED') THEN
            NEW.status := 'PAID';
        END IF;
    ELSIF NEW.paid_amount > 0 THEN
        NEW.payment_status := 'PARTIAL';
    END IF;
    
    NEW.updated_at := CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;


ALTER FUNCTION public.update_payable_outstanding() OWNER TO postgres;

--
-- TOC entry 326 (class 1255 OID 51521)
-- Name: update_payable_timestamp(); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE FUNCTION public.update_payable_timestamp() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;


ALTER FUNCTION public.update_payable_timestamp() OWNER TO postgres;

--
-- TOC entry 317 (class 1255 OID 42324)
-- Name: update_pco_updated_at(); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE FUNCTION public.update_pco_updated_at() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;


ALTER FUNCTION public.update_pco_updated_at() OWNER TO postgres;

--
-- TOC entry 352 (class 1255 OID 33325)
-- Name: update_pool_available_count(); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE FUNCTION public.update_pool_available_count() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        -- Only decrement if VIBAN is being created as ASSIGNED (not RETURNED/available)
        -- VIBANs created with status='RETURNED' are available in pool, don't decrement
        IF NEW.pool_id IS NOT NULL AND NEW.status != 'RETURNED' THEN
            UPDATE viban_pools 
            SET available_count = GREATEST(available_count - 1, 0),
                status = CASE WHEN available_count <= 1 THEN 'EXHAUSTED' ELSE status END,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = NEW.pool_id;
        END IF;
        RETURN NEW;
        
    ELSIF TG_OP = 'UPDATE' THEN
        -- VIBAN assigned from pool (RETURNED -> ACTIVE or other assigned status)
        IF OLD.status = 'RETURNED' AND NEW.status != 'RETURNED' AND NEW.pool_id IS NOT NULL THEN
            UPDATE viban_pools 
            SET available_count = GREATEST(available_count - 1, 0),
                status = CASE WHEN available_count <= 1 THEN 'EXHAUSTED' ELSE status END,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = NEW.pool_id;
        -- VIBAN returned to pool (any status -> RETURNED)
        ELSIF OLD.status != 'RETURNED' AND NEW.status = 'RETURNED' AND NEW.pool_id IS NOT NULL THEN
            UPDATE viban_pools 
            SET available_count = LEAST(available_count + 1, pool_size),
                status = CASE WHEN status = 'EXHAUSTED' THEN 'ACTIVE' ELSE status END,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = NEW.pool_id;
        END IF;
        RETURN NEW;
    END IF;
    RETURN NULL;
END;
$$;


ALTER FUNCTION public.update_pool_available_count() OWNER TO postgres;

--
-- TOC entry 375 (class 1255 OID 33586)
-- Name: update_receivable_outstanding(); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE FUNCTION public.update_receivable_outstanding() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    NEW.outstanding_amount := NEW.net_amount - COALESCE(NEW.paid_amount, 0);
    
    -- Auto-update status based on payment
    IF NEW.paid_amount >= NEW.net_amount THEN
        NEW.status := 'PAID';
        NEW.payment_status := 'COMPLETE';
    ELSIF NEW.paid_amount > 0 THEN
        IF NEW.status NOT IN ('DISPUTED', 'CANCELLED') THEN
            NEW.status := 'PARTIAL';
        END IF;
        NEW.payment_status := 'PARTIAL';
    ELSIF NEW.due_date IS NOT NULL AND CURRENT_DATE > NEW.due_date THEN
        IF NEW.status = 'OPEN' THEN
            NEW.status := 'OVERDUE';
        END IF;
    END IF;
    
    -- Handle overpayment
    IF NEW.paid_amount > NEW.net_amount THEN
        NEW.payment_status := 'OVERPAID';
    END IF;
    
    NEW.updated_at := CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;


ALTER FUNCTION public.update_receivable_outstanding() OWNER TO postgres;

--
-- TOC entry 350 (class 1255 OID 33323)
-- Name: update_viban_usage_stats(); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE FUNCTION public.update_viban_usage_stats() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    UPDATE vibans 
    SET 
        times_used = times_used + 1,
        total_amount_received = total_amount_received + NEW.amount,
        last_used_at = NEW.received_at,
        last_payment_amount = NEW.amount,
        updated_at = CURRENT_TIMESTAMP,
        -- Update status if single_use
        status = CASE 
            WHEN single_use = true THEN 'PAID'
            WHEN expected_amount IS NOT NULL AND (total_amount_received + NEW.amount) >= expected_amount THEN 'PAID'
            WHEN expected_amount IS NOT NULL AND (total_amount_received + NEW.amount) > 0 THEN 'PARTIAL'
            ELSE status
        END
    WHERE id = NEW.viban_id;
    RETURN NEW;
END;
$$;


ALTER FUNCTION public.update_viban_usage_stats() OWNER TO postgres;

--
-- TOC entry 363 (class 1255 OID 51203)
-- Name: validate_pobo_eligibility(uuid, uuid, numeric); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE FUNCTION public.validate_pobo_eligibility(p_party_id uuid, p_paying_entity_id uuid, p_amount numeric) RETURNS TABLE(is_valid boolean, reason text, party_status character varying, kyc_status character varying, pobo_eligible boolean, available_credit numeric)
    LANGUAGE plpgsql
    AS $$
DECLARE
    v_party RECORD;
BEGIN
    -- Fetch party details
    SELECT 
        p.status,
        p.kyc_status,
        p.pobo_eligible,
        p.is_intercompany,
        p.ic_credit_limit,
        p.ic_current_exposure
    INTO v_party
    FROM parties p
    WHERE p.id = p_party_id;
    
    IF NOT FOUND THEN
        RETURN QUERY SELECT 
            FALSE, 
            'Party not found'::TEXT,
            NULL::VARCHAR(20),
            NULL::VARCHAR(20),
            NULL::BOOLEAN,
            NULL::NUMERIC(19, 4);
        RETURN;
    END IF;
    
    -- Check party status
    IF v_party.status != 'ACTIVE' THEN
        RETURN QUERY SELECT 
            FALSE, 
            'Party is not active'::TEXT,
            v_party.status,
            v_party.kyc_status,
            v_party.pobo_eligible,
            NULL::NUMERIC(19, 4);
        RETURN;
    END IF;
    
    -- Check KYC status
    IF v_party.kyc_status NOT IN ('VERIFIED', 'EXEMPTED') THEN
        RETURN QUERY SELECT 
            FALSE, 
            'Party KYC not verified'::TEXT,
            v_party.status,
            v_party.kyc_status,
            v_party.pobo_eligible,
            NULL::NUMERIC(19, 4);
        RETURN;
    END IF;
    
    -- Check POBO eligibility
    IF NOT COALESCE(v_party.pobo_eligible, FALSE) THEN
        RETURN QUERY SELECT 
            FALSE, 
            'Party not POBO eligible'::TEXT,
            v_party.status,
            v_party.kyc_status,
            v_party.pobo_eligible,
            NULL::NUMERIC(19, 4);
        RETURN;
    END IF;
    
    -- Check IC credit limit if intercompany
    IF v_party.is_intercompany AND v_party.ic_credit_limit IS NOT NULL THEN
        DECLARE
            v_available NUMERIC(19, 4);
        BEGIN
            v_available := v_party.ic_credit_limit - COALESCE(v_party.ic_current_exposure, 0);
            
            IF v_available < p_amount THEN
                RETURN QUERY SELECT 
                    FALSE, 
                    format('Insufficient IC credit limit. Available: %s, Required: %s', v_available, p_amount)::TEXT,
                    v_party.status,
                    v_party.kyc_status,
                    v_party.pobo_eligible,
                    v_available;
                RETURN;
            END IF;
        END;
    END IF;
    
    -- All checks passed
    RETURN QUERY SELECT 
        TRUE, 
        'POBO eligible'::TEXT,
        v_party.status,
        v_party.kyc_status,
        v_party.pobo_eligible,
        CASE 
            WHEN v_party.is_intercompany AND v_party.ic_credit_limit IS NOT NULL 
            THEN v_party.ic_credit_limit - COALESCE(v_party.ic_current_exposure, 0)
            ELSE NULL
        END;
END;
$$;


ALTER FUNCTION public.validate_pobo_eligibility(p_party_id uuid, p_paying_entity_id uuid, p_amount numeric) OWNER TO postgres;

--
-- TOC entry 6933 (class 0 OID 0)
-- Dependencies: 363
-- Name: FUNCTION validate_pobo_eligibility(p_party_id uuid, p_paying_entity_id uuid, p_amount numeric); Type: COMMENT; Schema: public; Owner: postgres
--

COMMENT ON FUNCTION public.validate_pobo_eligibility(p_party_id uuid, p_paying_entity_id uuid, p_amount numeric) IS 'Validates if a party is eligible for POBO payment and returns detailed status.';


--
-- TOC entry 333 (class 1255 OID 33328)
-- Name: validate_viban(character varying); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE FUNCTION public.validate_viban(p_viban character varying) RETURNS boolean
    LANGUAGE plpgsql
    AS $$
DECLARE
    v_rearranged VARCHAR;
    v_numeric VARCHAR;
    v_char CHAR;
    v_code INTEGER;
BEGIN
    IF LENGTH(p_viban) < 15 OR LENGTH(p_viban) > 34 THEN
        RETURN FALSE;
    END IF;
    
    -- Rearrange: move first 4 chars to end
    v_rearranged := SUBSTRING(p_viban FROM 5) || SUBSTRING(p_viban FROM 1 FOR 4);
    
    -- Convert letters to numbers
    v_numeric := '';
    FOR i IN 1..LENGTH(v_rearranged) LOOP
        v_char := SUBSTRING(v_rearranged FROM i FOR 1);
        IF v_char ~ '[A-Z]' THEN
            v_code := ASCII(v_char) - ASCII('A') + 10;
            v_numeric := v_numeric || v_code::VARCHAR;
        ELSE
            v_numeric := v_numeric || v_char;
        END IF;
    END LOOP;
    
    -- Valid if MOD 97 = 1
    RETURN (v_numeric::NUMERIC % 97) = 1;
END;
$$;


ALTER FUNCTION public.validate_viban(p_viban character varying) OWNER TO postgres;

SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- TOC entry 287 (class 1259 OID 50544)
-- Name: account_attachments; Type: TABLE; Schema: public; Owner: vam_user
--

CREATE TABLE public.account_attachments (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    virtual_account_id uuid NOT NULL,
    legal_entity_id uuid NOT NULL,
    relationship_type character varying(30) DEFAULT 'OWNER'::character varying NOT NULL,
    is_primary boolean DEFAULT true,
    description character varying(500),
    effective_from date DEFAULT CURRENT_DATE NOT NULL,
    effective_to date,
    max_transaction_amount numeric(19,4),
    daily_limit numeric(19,4),
    authorized_transaction_types character varying(200),
    requires_dual_auth boolean DEFAULT false,
    collateral_percent numeric(5,2),
    secured_facility_id uuid,
    status character varying(20) DEFAULT 'ACTIVE'::character varying,
    approved_by character varying(100),
    approved_at timestamp without time zone,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by character varying(100),
    updated_by character varying(100),
    version bigint DEFAULT 0,
    CONSTRAINT chk_aa_relationship_type CHECK (((relationship_type)::text = ANY ((ARRAY['OWNER'::character varying, 'BENEFICIARY'::character varying, 'AUTHORIZED'::character varying, 'GUARANTOR'::character varying, 'COLLATERAL'::character varying])::text[]))),
    CONSTRAINT chk_aa_status CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'PENDING_APPROVAL'::character varying, 'SUSPENDED'::character varying, 'EXPIRED'::character varying, 'TERMINATED'::character varying])::text[])))
);


ALTER TABLE public.account_attachments OWNER TO vam_user;

--
-- TOC entry 6941 (class 0 OID 0)
-- Dependencies: 287
-- Name: TABLE account_attachments; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON TABLE public.account_attachments IS 'Links VirtualAccounts to LegalEntities';


--
-- TOC entry 6942 (class 0 OID 0)
-- Dependencies: 287
-- Name: COLUMN account_attachments.relationship_type; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.account_attachments.relationship_type IS 'OWNER, BENEFICIARY, AUTHORIZED, GUARANTOR, COLLATERAL';


--
-- TOC entry 6943 (class 0 OID 0)
-- Dependencies: 287
-- Name: COLUMN account_attachments.is_primary; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.account_attachments.is_primary IS 'Primary relationship for this account';


--
-- TOC entry 6944 (class 0 OID 0)
-- Dependencies: 287
-- Name: COLUMN account_attachments.max_transaction_amount; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.account_attachments.max_transaction_amount IS 'Max per-transaction limit (for AUTHORIZED)';


--
-- TOC entry 6945 (class 0 OID 0)
-- Dependencies: 287
-- Name: COLUMN account_attachments.collateral_percent; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.account_attachments.collateral_percent IS 'Collateral percentage (for COLLATERAL)';


--
-- TOC entry 222 (class 1259 OID 30655)
-- Name: beneficiaries; Type: TABLE; Schema: public; Owner: vam_user
--

CREATE TABLE public.beneficiaries (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone,
    created_by character varying(255),
    updated_at timestamp(6) without time zone,
    updated_by character varying(255),
    version bigint,
    account_number character varying(255),
    address_line1 character varying(255),
    address_line2 character varying(255),
    bank_name character varying(255),
    beneficiary_name character varying(255) NOT NULL,
    beneficiary_type character varying(255),
    city character varying(255),
    corporate_id uuid NOT NULL,
    country_code character varying(2),
    currency_code character varying(3),
    iban character varying(34),
    status character varying(255),
    swift_code character varying(11),
    validation_status character varying(255),
    CONSTRAINT beneficiaries_beneficiary_type_check CHECK (((beneficiary_type)::text = ANY ((ARRAY['INDIVIDUAL'::character varying, 'CORPORATE'::character varying])::text[]))),
    CONSTRAINT beneficiaries_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'INACTIVE'::character varying, 'BLOCKED'::character varying])::text[]))),
    CONSTRAINT beneficiaries_validation_status_check CHECK (((validation_status)::text = ANY ((ARRAY['PENDING'::character varying, 'VERIFIED'::character varying, 'FAILED'::character varying, 'EXPIRED'::character varying])::text[])))
);


ALTER TABLE public.beneficiaries OWNER TO vam_user;

--
-- TOC entry 276 (class 1259 OID 33927)
-- Name: calculated_charges; Type: TABLE; Schema: public; Owner: vam_user
--

CREATE TABLE public.calculated_charges (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    reference_type character varying(30) NOT NULL,
    reference_id uuid NOT NULL,
    charge_config_id uuid NOT NULL,
    charge_code character varying(30) NOT NULL,
    charge_type character varying(30) NOT NULL,
    base_amount numeric(18,2) NOT NULL,
    calculated_charge numeric(18,2) NOT NULL,
    waived_amount numeric(18,2) DEFAULT 0,
    final_charge numeric(18,2) NOT NULL,
    currency_code character varying(3) NOT NULL,
    calculation_method character varying(30),
    tier_applied integer,
    waiver_reason character varying(200),
    waiver_approved_by character varying(100),
    status character varying(20) DEFAULT 'CALCULATED'::character varying,
    applied_at timestamp without time zone,
    calculated_by character varying(100),
    calculation_notes text,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    va_id uuid,
    version bigint DEFAULT 0,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by character varying(100),
    updated_by character varying(100)
);


ALTER TABLE public.calculated_charges OWNER TO vam_user;

--
-- TOC entry 6946 (class 0 OID 0)
-- Dependencies: 276
-- Name: TABLE calculated_charges; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON TABLE public.calculated_charges IS 'Audit trail of calculated charges';


--
-- TOC entry 275 (class 1259 OID 33896)
-- Name: calculated_taxes; Type: TABLE; Schema: public; Owner: vam_user
--

CREATE TABLE public.calculated_taxes (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    reference_type character varying(30) NOT NULL,
    reference_id uuid NOT NULL,
    line_item_id uuid,
    tax_config_id uuid NOT NULL,
    tax_code character varying(30) NOT NULL,
    tax_type character varying(30) NOT NULL,
    base_amount numeric(18,2) NOT NULL,
    tax_rate numeric(8,4) NOT NULL,
    calculated_tax numeric(18,2) NOT NULL,
    adjusted_tax numeric(18,2),
    final_tax numeric(18,2) NOT NULL,
    currency_code character varying(3) NOT NULL,
    is_withholding boolean DEFAULT false,
    withheld_by character varying(200),
    withholding_certificate character varying(100),
    status character varying(20) DEFAULT 'CALCULATED'::character varying,
    applied_at timestamp without time zone,
    calculated_by character varying(100),
    calculation_notes text,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    va_id uuid,
    version bigint DEFAULT 0,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by character varying(100),
    updated_by character varying(100)
);


ALTER TABLE public.calculated_taxes OWNER TO vam_user;

--
-- TOC entry 6947 (class 0 OID 0)
-- Dependencies: 275
-- Name: TABLE calculated_taxes; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON TABLE public.calculated_taxes IS 'Audit trail of calculated taxes';


--
-- TOC entry 274 (class 1259 OID 33864)
-- Name: charge_configurations; Type: TABLE; Schema: public; Owner: vam_user
--

CREATE TABLE public.charge_configurations (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    charge_code character varying(30) NOT NULL,
    charge_name character varying(100) NOT NULL,
    description text,
    charge_type character varying(30) NOT NULL,
    charge_category character varying(30),
    fixed_amount numeric(18,2) DEFAULT 0,
    percentage_rate numeric(8,4) DEFAULT 0,
    currency_code character varying(3) DEFAULT 'AED'::character varying,
    minimum_charge numeric(18,2) DEFAULT 0,
    maximum_charge numeric(18,2),
    tier_config jsonb,
    applies_to_payment_method character varying(30),
    applies_to_priority character varying(20),
    applies_to_currency character varying(3),
    applies_to_country character varying(3),
    is_cross_border boolean DEFAULT false,
    is_domestic boolean DEFAULT true,
    waiver_threshold numeric(18,2),
    waiver_for_vip boolean DEFAULT false,
    waiver_for_bulk boolean DEFAULT false,
    income_account character varying(50),
    expense_account character varying(50),
    effective_from date DEFAULT CURRENT_DATE NOT NULL,
    effective_to date,
    status character varying(20) DEFAULT 'ACTIVE'::character varying,
    created_by character varying(100),
    updated_by character varying(100),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    applies_to_channel character varying(30),
    applies_to_wallet_operation character varying(30),
    applies_to_kyc_tier character varying(20),
    version bigint DEFAULT 0
);


ALTER TABLE public.charge_configurations OWNER TO vam_user;

--
-- TOC entry 6948 (class 0 OID 0)
-- Dependencies: 274
-- Name: TABLE charge_configurations; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON TABLE public.charge_configurations IS 'Fee schedules for payment processing';


--
-- TOC entry 223 (class 1259 OID 30668)
-- Name: corporates; Type: TABLE; Schema: public; Owner: vam_user
--

CREATE TABLE public.corporates (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone,
    created_by character varying(255),
    updated_at timestamp(6) without time zone,
    updated_by character varying(255),
    version bigint DEFAULT 0,
    business_description text,
    corporate_id character varying(255) NOT NULL,
    incorporation_country character varying(255),
    incorporation_date date,
    industry_sector character varying(255),
    kyc_status character varying(255),
    legal_entity_type character varying(255),
    legal_name character varying(255) NOT NULL,
    primary_contact_email character varying(255),
    primary_contact_name character varying(255),
    primary_contact_phone character varying(255),
    registration_number character varying(255),
    risk_rating character varying(255),
    status character varying(255),
    tax_id character varying(255),
    trade_name character varying(255),
    website character varying(255),
    CONSTRAINT corporates_kyc_status_check CHECK (((kyc_status)::text = ANY ((ARRAY['PENDING'::character varying, 'APPROVED'::character varying, 'REJECTED'::character varying, 'EXPIRED'::character varying, 'UNDER_REVIEW'::character varying])::text[]))),
    CONSTRAINT corporates_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'INACTIVE'::character varying, 'SUSPENDED'::character varying, 'PENDING_APPROVAL'::character varying])::text[])))
);


ALTER TABLE public.corporates OWNER TO vam_user;

--
-- TOC entry 288 (class 1259 OID 50672)
-- Name: credit_agreements; Type: TABLE; Schema: public; Owner: vam_user
--

CREATE TABLE public.credit_agreements (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    external_reference character varying(50),
    agreement_name character varying(200) NOT NULL,
    corporate_id uuid NOT NULL,
    bank_code character varying(20),
    bank_name character varying(100),
    bank_branch character varying(100),
    total_limit numeric(19,4) NOT NULL,
    limit_currency character varying(3) NOT NULL,
    total_utilized numeric(19,4) DEFAULT 0,
    available_limit numeric(19,4),
    effective_date date,
    expiry_date date,
    last_review_date date,
    next_review_date date,
    arrangement_fee_percent numeric(8,5),
    commitment_fee_percent numeric(8,5),
    base_rate_type character varying(20),
    spread_bps numeric(10,4),
    status character varying(20) DEFAULT 'ACTIVE'::character varying,
    has_covenants boolean DEFAULT false,
    covenant_details text,
    last_sync_at timestamp without time zone,
    source_system character varying(50),
    notes character varying(1000),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by character varying(100),
    updated_by character varying(100),
    version bigint DEFAULT 0,
    CONSTRAINT chk_ca_status CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'PENDING'::character varying, 'ACTIVE'::character varying, 'SUSPENDED'::character varying, 'EXPIRED'::character varying, 'TERMINATED'::character varying, 'CANCELLED'::character varying])::text[])))
);


ALTER TABLE public.credit_agreements OWNER TO vam_user;

--
-- TOC entry 6949 (class 0 OID 0)
-- Dependencies: 288
-- Name: TABLE credit_agreements; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON TABLE public.credit_agreements IS 'Master credit agreements from Core Banking System';


--
-- TOC entry 6950 (class 0 OID 0)
-- Dependencies: 288
-- Name: COLUMN credit_agreements.total_limit; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.credit_agreements.total_limit IS 'Total agreement limit amount';


--
-- TOC entry 6951 (class 0 OID 0)
-- Dependencies: 288
-- Name: COLUMN credit_agreements.total_utilized; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.credit_agreements.total_utilized IS 'Sum utilized across all facilities';


--
-- TOC entry 289 (class 1259 OID 50698)
-- Name: credit_facilities; Type: TABLE; Schema: public; Owner: vam_user
--

CREATE TABLE public.credit_facilities (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    external_reference character varying(50),
    facility_name character varying(200) NOT NULL,
    credit_agreement_id uuid NOT NULL,
    corporate_id uuid NOT NULL,
    facility_type character varying(30) NOT NULL,
    facility_sub_type character varying(30),
    sanctioned_limit numeric(19,4) NOT NULL,
    drawing_power numeric(19,4),
    current_outstanding numeric(19,4) DEFAULT 0,
    available_limit numeric(19,4),
    facility_currency character varying(3) NOT NULL,
    physical_account_id uuid,
    sanction_date date,
    effective_date date,
    expiry_date date,
    last_renewal_date date,
    interest_rate_type character varying(20),
    base_rate_type character varying(20),
    base_rate_value numeric(8,5),
    spread_percent numeric(8,5),
    effective_rate numeric(8,5),
    penal_rate numeric(8,5),
    processing_fee_percent numeric(8,5),
    commitment_fee_percent numeric(8,5),
    repayment_frequency character varying(20),
    emi_amount numeric(19,4),
    total_installments integer,
    installments_paid integer DEFAULT 0,
    is_secured boolean DEFAULT false,
    security_type character varying(50),
    security_value numeric(19,4),
    status character varying(20) DEFAULT 'ACTIVE'::character varying,
    last_sync_at timestamp without time zone,
    source_system character varying(50),
    notes character varying(1000),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by character varying(100),
    updated_by character varying(100),
    version bigint DEFAULT 0,
    CONSTRAINT chk_cf_status CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'PENDING_APPROVAL'::character varying, 'ACTIVE'::character varying, 'SUSPENDED'::character varying, 'EXPIRED'::character varying, 'CLOSED'::character varying, 'CANCELLED'::character varying, 'OVERDUE'::character varying])::text[]))),
    CONSTRAINT chk_cf_type CHECK (((facility_type)::text = ANY ((ARRAY['OVERDRAFT'::character varying, 'REVOLVING_CREDIT'::character varying, 'TERM_LOAN'::character varying, 'WORKING_CAPITAL'::character varying, 'LETTER_OF_CREDIT'::character varying, 'BANK_GUARANTEE'::character varying, 'CASH_CREDIT'::character varying, 'BILL_DISCOUNTING'::character varying, 'EXPORT_CREDIT'::character varying, 'IMPORT_CREDIT'::character varying, 'PACKING_CREDIT'::character varying, 'BUYERS_CREDIT'::character varying, 'SUPPLIERS_CREDIT'::character varying])::text[])))
);


ALTER TABLE public.credit_facilities OWNER TO vam_user;

--
-- TOC entry 6952 (class 0 OID 0)
-- Dependencies: 289
-- Name: TABLE credit_facilities; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON TABLE public.credit_facilities IS 'Credit lines under master agreements';


--
-- TOC entry 6953 (class 0 OID 0)
-- Dependencies: 289
-- Name: COLUMN credit_facilities.facility_type; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.credit_facilities.facility_type IS 'OVERDRAFT, REVOLVING_CREDIT, TERM_LOAN, etc.';


--
-- TOC entry 6954 (class 0 OID 0)
-- Dependencies: 289
-- Name: COLUMN credit_facilities.sanctioned_limit; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.credit_facilities.sanctioned_limit IS 'Approved limit amount';


--
-- TOC entry 6955 (class 0 OID 0)
-- Dependencies: 289
-- Name: COLUMN credit_facilities.drawing_power; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.credit_facilities.drawing_power IS 'Available drawing power (may be less than sanctioned)';


--
-- TOC entry 290 (class 1259 OID 50735)
-- Name: credit_limits; Type: TABLE; Schema: public; Owner: vam_user
--

CREATE TABLE public.credit_limits (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    corporate_id uuid NOT NULL,
    limit_name character varying(200),
    limit_type character varying(20) NOT NULL,
    target_type character varying(30) NOT NULL,
    target_id uuid NOT NULL,
    credit_facility_id uuid,
    external_reference character varying(50),
    limit_amount numeric(19,4) NOT NULL,
    limit_currency character varying(3) NOT NULL,
    utilized_amount numeric(19,4) DEFAULT 0,
    available_amount numeric(19,4),
    held_amount numeric(19,4) DEFAULT 0,
    warning_threshold_percent numeric(5,2) DEFAULT 80.00,
    critical_threshold_percent numeric(5,2) DEFAULT 95.00,
    effective_from date,
    effective_to date,
    status character varying(20) DEFAULT 'ACTIVE'::character varying,
    approved_by character varying(100),
    approved_at timestamp without time zone,
    approval_notes character varying(500),
    last_sync_at timestamp without time zone,
    source_system character varying(50),
    notes character varying(1000),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by character varying(100),
    updated_by character varying(100),
    version bigint DEFAULT 0,
    parent_limit_id uuid,
    allocated_to_children numeric(19,4) DEFAULT 0,
    is_hard_limit boolean DEFAULT true,
    requires_approval boolean DEFAULT false,
    approval_threshold_percent numeric(5,2),
    CONSTRAINT chk_cl_limit_type CHECK (((limit_type)::text = ANY ((ARRAY['EXTERNAL'::character varying, 'INTERNAL'::character varying])::text[]))),
    CONSTRAINT chk_cl_status CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'PENDING_APPROVAL'::character varying, 'ACTIVE'::character varying, 'SUSPENDED'::character varying, 'EXPIRED'::character varying, 'CANCELLED'::character varying, 'BREACHED'::character varying])::text[]))),
    CONSTRAINT chk_cl_target_type CHECK (((target_type)::text = ANY ((ARRAY['VIRTUAL_ACCOUNT'::character varying, 'LEGAL_ENTITY'::character varying, 'AGGREGATION_NODE'::character varying, 'SHADOW_ACCOUNT'::character varying, 'ROOT'::character varying, 'CORPORATE'::character varying])::text[])))
);


ALTER TABLE public.credit_limits OWNER TO vam_user;

--
-- TOC entry 6956 (class 0 OID 0)
-- Dependencies: 290
-- Name: TABLE credit_limits; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON TABLE public.credit_limits IS 'External (CBS) and Internal (CFO) limit allocations';


--
-- TOC entry 6957 (class 0 OID 0)
-- Dependencies: 290
-- Name: COLUMN credit_limits.limit_type; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.credit_limits.limit_type IS 'EXTERNAL (from bank) or INTERNAL (from CFO)';


--
-- TOC entry 6958 (class 0 OID 0)
-- Dependencies: 290
-- Name: COLUMN credit_limits.target_type; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.credit_limits.target_type IS 'What the limit applies to: VIRTUAL_ACCOUNT, LEGAL_ENTITY, etc.';


--
-- TOC entry 6959 (class 0 OID 0)
-- Dependencies: 290
-- Name: COLUMN credit_limits.utilized_amount; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.credit_limits.utilized_amount IS 'Current utilization of the limit';


--
-- TOC entry 6960 (class 0 OID 0)
-- Dependencies: 290
-- Name: COLUMN credit_limits.held_amount; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.credit_limits.held_amount IS 'Amount held/blocked pending utilization';


--
-- TOC entry 6961 (class 0 OID 0)
-- Dependencies: 290
-- Name: COLUMN credit_limits.parent_limit_id; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.credit_limits.parent_limit_id IS 'Parent limit ID for hierarchical limits (Group -> Entity)';


--
-- TOC entry 6962 (class 0 OID 0)
-- Dependencies: 290
-- Name: COLUMN credit_limits.allocated_to_children; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.credit_limits.allocated_to_children IS 'Amount allocated from this limit to child limits (for GROUP limits)';


--
-- TOC entry 6963 (class 0 OID 0)
-- Dependencies: 290
-- Name: COLUMN credit_limits.is_hard_limit; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.credit_limits.is_hard_limit IS 'TRUE=blocks transactions, FALSE=warning only';


--
-- TOC entry 6964 (class 0 OID 0)
-- Dependencies: 290
-- Name: COLUMN credit_limits.requires_approval; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.credit_limits.requires_approval IS 'Whether transactions need approval above threshold';


--
-- TOC entry 6965 (class 0 OID 0)
-- Dependencies: 290
-- Name: COLUMN credit_limits.approval_threshold_percent; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.credit_limits.approval_threshold_percent IS 'Utilization % that triggers approval requirement';


--
-- TOC entry 283 (class 1259 OID 34173)
-- Name: escrow_contracts; Type: TABLE; Schema: public; Owner: vam_user
--

CREATE TABLE public.escrow_contracts (
    id uuid NOT NULL,
    escrow_reference character varying(50) NOT NULL,
    escrow_type character varying(20) NOT NULL,
    buyer_id uuid,
    buyer_name character varying(200),
    seller_id uuid,
    seller_name character varying(200),
    escrow_va_id uuid,
    corporate_id uuid,
    program_id uuid,
    contract_amount numeric(18,2) NOT NULL,
    funded_amount numeric(18,2) DEFAULT 0,
    released_amount numeric(18,2) DEFAULT 0,
    current_balance numeric(18,2) DEFAULT 0,
    currency_code character varying(3) DEFAULT 'AED'::character varying,
    setup_fee numeric(18,2),
    setup_fee_charged boolean DEFAULT false,
    total_fees_charged numeric(18,2) DEFAULT 0,
    status character varying(20) NOT NULL,
    expiry_date date,
    funded_at timestamp without time zone,
    released_at timestamp without time zone,
    disputed_at timestamp without time zone,
    cancelled_at timestamp without time zone,
    release_conditions text,
    dispute_reason character varying(500),
    cancellation_reason character varying(500),
    created_by character varying(100),
    last_modified_by character varying(100),
    created_at timestamp without time zone DEFAULT now(),
    updated_at timestamp without time zone DEFAULT now(),
    updated_by character varying(255),
    version bigint
);


ALTER TABLE public.escrow_contracts OWNER TO vam_user;

--
-- TOC entry 282 (class 1259 OID 34159)
-- Name: exception_number_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

CREATE SEQUENCE public.exception_number_seq
    START WITH 1000
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.exception_number_seq OWNER TO postgres;

--
-- TOC entry 281 (class 1259 OID 34113)
-- Name: exception_transactions; Type: TABLE; Schema: public; Owner: vam_user
--

CREATE TABLE public.exception_transactions (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    exception_number character varying(30) NOT NULL,
    exception_type character varying(30) NOT NULL,
    original_transaction_id uuid,
    original_va_id uuid,
    exception_va_id uuid NOT NULL,
    amount numeric(18,2) NOT NULL,
    currency_code character varying(3) NOT NULL,
    bank_reference character varying(100),
    remitter_info character varying(500),
    description text,
    status character varying(20) DEFAULT 'OPEN'::character varying NOT NULL,
    target_va_id uuid,
    resolution_notes text,
    resolved_by character varying(100),
    resolved_at timestamp without time zone,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by character varying(100),
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    program_id uuid,
    value_date date,
    remitter_name character varying(200),
    remitter_account character varying(50),
    remitter_reference character varying(100),
    notes text,
    priority character varying(20) DEFAULT 'NORMAL'::character varying,
    resolution_transaction_id uuid,
    correlation_id character varying(50),
    updated_by character varying(255),
    version bigint,
    CONSTRAINT chk_exception_status CHECK (((status)::text = ANY ((ARRAY['OPEN'::character varying, 'IN_PROGRESS'::character varying, 'RESOLVED'::character varying, 'WRITTEN_OFF'::character varying, 'RETURNED'::character varying])::text[]))),
    CONSTRAINT chk_exception_type CHECK (((exception_type)::text = ANY ((ARRAY['UNMATCHED_PAYMENT'::character varying, 'RECONCILIATION_DIFF'::character varying, 'FAILED_PAYMENT'::character varying, 'INVALID_VIBAN'::character varying, 'AMOUNT_MISMATCH'::character varying, 'DUPLICATE_PAYMENT'::character varying, 'BANK_INTEREST'::character varying, 'FX_DIFFERENCE'::character varying, 'SYSTEM_ERROR'::character varying, 'MISSING_SETTLEMENT_VA'::character varying])::text[])))
);


ALTER TABLE public.exception_transactions OWNER TO vam_user;

--
-- TOC entry 294 (class 1259 OID 51009)
-- Name: fx_rates; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.fx_rates (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    from_currency character varying(3) NOT NULL,
    to_currency character varying(3) NOT NULL,
    rate numeric(19,8) NOT NULL,
    bid_rate numeric(19,8),
    ask_rate numeric(19,8),
    rate_type character varying(10) DEFAULT 'MID'::character varying,
    rate_date date DEFAULT CURRENT_DATE NOT NULL,
    rate_time time without time zone,
    source character varying(50) DEFAULT 'MANUAL'::character varying,
    source_reference character varying(100),
    effective_from timestamp without time zone DEFAULT now(),
    effective_to timestamp without time zone,
    is_active boolean DEFAULT true,
    spread_bps numeric(10,4),
    corporate_id uuid,
    program_id uuid,
    created_at timestamp without time zone DEFAULT now(),
    updated_at timestamp without time zone DEFAULT now(),
    created_by character varying(100),
    updated_by character varying(100),
    approved_by character varying(255),
    CONSTRAINT fx_rates_different_currencies CHECK (((from_currency)::text <> (to_currency)::text)),
    CONSTRAINT fx_rates_positive_rate CHECK ((rate > (0)::numeric)),
    CONSTRAINT fx_rates_rate_type_check CHECK (((rate_type)::text = ANY ((ARRAY['BID'::character varying, 'ASK'::character varying, 'MID'::character varying])::text[]))),
    CONSTRAINT fx_rates_source_check CHECK (((source)::text = ANY ((ARRAY['CENTRAL_BANK'::character varying, 'REUTERS'::character varying, 'BLOOMBERG'::character varying, 'MANUAL'::character varying, 'INTERNAL'::character varying])::text[]))),
    CONSTRAINT fx_rates_valid_spread CHECK (((bid_rate IS NULL) OR (ask_rate IS NULL) OR (bid_rate <= ask_rate)))
);


ALTER TABLE public.fx_rates OWNER TO postgres;

--
-- TOC entry 6967 (class 0 OID 0)
-- Dependencies: 294
-- Name: TABLE fx_rates; Type: COMMENT; Schema: public; Owner: postgres
--

COMMENT ON TABLE public.fx_rates IS 'Foreign exchange rates for multi-currency operations';


--
-- TOC entry 6968 (class 0 OID 0)
-- Dependencies: 294
-- Name: COLUMN fx_rates.rate; Type: COMMENT; Schema: public; Owner: postgres
--

COMMENT ON COLUMN public.fx_rates.rate IS 'Mid-market exchange rate (1 from_currency = rate * to_currency)';


--
-- TOC entry 6969 (class 0 OID 0)
-- Dependencies: 294
-- Name: COLUMN fx_rates.bid_rate; Type: COMMENT; Schema: public; Owner: postgres
--

COMMENT ON COLUMN public.fx_rates.bid_rate IS 'Bank buying rate (lower than mid)';


--
-- TOC entry 6970 (class 0 OID 0)
-- Dependencies: 294
-- Name: COLUMN fx_rates.ask_rate; Type: COMMENT; Schema: public; Owner: postgres
--

COMMENT ON COLUMN public.fx_rates.ask_rate IS 'Bank selling rate (higher than mid)';


--
-- TOC entry 6971 (class 0 OID 0)
-- Dependencies: 294
-- Name: COLUMN fx_rates.source; Type: COMMENT; Schema: public; Owner: postgres
--

COMMENT ON COLUMN public.fx_rates.source IS 'Rate source: CENTRAL_BANK, REUTERS, BLOOMBERG, MANUAL, INTERNAL';


--
-- TOC entry 6972 (class 0 OID 0)
-- Dependencies: 294
-- Name: COLUMN fx_rates.spread_bps; Type: COMMENT; Schema: public; Owner: postgres
--

COMMENT ON COLUMN public.fx_rates.spread_bps IS 'Spread in basis points between bid and ask';


--
-- TOC entry 6973 (class 0 OID 0)
-- Dependencies: 294
-- Name: COLUMN fx_rates.corporate_id; Type: COMMENT; Schema: public; Owner: postgres
--

COMMENT ON COLUMN public.fx_rates.corporate_id IS 'If set, this is a corporate-specific negotiated rate';


--
-- TOC entry 6974 (class 0 OID 0)
-- Dependencies: 294
-- Name: COLUMN fx_rates.approved_by; Type: COMMENT; Schema: public; Owner: postgres
--

COMMENT ON COLUMN public.fx_rates.approved_by IS 'User who approved this FX rate (optional)';


--
-- TOC entry 254 (class 1259 OID 33044)
-- Name: hierarchy_level_configs; Type: TABLE; Schema: public; Owner: vam_user
--

CREATE TABLE public.hierarchy_level_configs (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    program_id uuid NOT NULL,
    level_number integer NOT NULL,
    level_name character varying(50) NOT NULL,
    dimension_type character varying(50) NOT NULL,
    is_required boolean DEFAULT true,
    allowed_values jsonb,
    description character varying(255),
    display_order integer,
    icon character varying(50),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by character varying(100),
    updated_by character varying(100),
    version bigint DEFAULT 0,
    CONSTRAINT hierarchy_level_configs_level_number_check CHECK (((level_number >= 1) AND (level_number <= 7)))
);


ALTER TABLE public.hierarchy_level_configs OWNER TO vam_user;

--
-- TOC entry 6976 (class 0 OID 0)
-- Dependencies: 254
-- Name: TABLE hierarchy_level_configs; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON TABLE public.hierarchy_level_configs IS 'Configures the meaning of each hierarchy level per program';


--
-- TOC entry 6977 (class 0 OID 0)
-- Dependencies: 254
-- Name: COLUMN hierarchy_level_configs.level_number; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.hierarchy_level_configs.level_number IS 'Level 1-7, where 1 is root (MASTER) and 7 is leaf (VIRTUAL_ACCOUNT)';


--
-- TOC entry 6978 (class 0 OID 0)
-- Dependencies: 254
-- Name: COLUMN hierarchy_level_configs.dimension_type; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.hierarchy_level_configs.dimension_type IS 'Type of dimension: CURRENCY, REGION, ENTITY, DEPARTMENT, ACCOUNT_TYPE, etc.';


--
-- TOC entry 6979 (class 0 OID 0)
-- Dependencies: 254
-- Name: COLUMN hierarchy_level_configs.allowed_values; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.hierarchy_level_configs.allowed_values IS 'JSON array of allowed values for this level, null means any value';


--
-- TOC entry 256 (class 1259 OID 33125)
-- Name: hierarchy_node_balance_history; Type: TABLE; Schema: public; Owner: vam_user
--

CREATE TABLE public.hierarchy_node_balance_history (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    node_id uuid NOT NULL,
    program_id uuid NOT NULL,
    aggregated_balance numeric(18,2) NOT NULL,
    available_balance numeric(18,2),
    held_balance numeric(18,2),
    child_count integer,
    balance_change numeric(18,2),
    change_source character varying(50),
    change_reason character varying(255),
    snapshot_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    snapshot_date date GENERATED ALWAYS AS (date(snapshot_at)) STORED
);


ALTER TABLE public.hierarchy_node_balance_history OWNER TO vam_user;

--
-- TOC entry 6980 (class 0 OID 0)
-- Dependencies: 256
-- Name: TABLE hierarchy_node_balance_history; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON TABLE public.hierarchy_node_balance_history IS 'Historical balance snapshots for hierarchy nodes';


--
-- TOC entry 255 (class 1259 OID 33069)
-- Name: hierarchy_nodes; Type: TABLE; Schema: public; Owner: vam_user
--

CREATE TABLE public.hierarchy_nodes (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    program_id uuid NOT NULL,
    parent_id uuid,
    level_number integer NOT NULL,
    node_code character varying(50) NOT NULL,
    node_name character varying(100) NOT NULL,
    node_type character varying(20) NOT NULL,
    currency_code character varying(3),
    dimension_value character varying(100),
    materialized_path character varying(500) NOT NULL,
    path_depth integer GENERATED ALWAYS AS ((cardinality(string_to_array((materialized_path)::text, '/'::text)) - 1)) STORED,
    is_leaf boolean DEFAULT false,
    child_count integer DEFAULT 0,
    virtual_account_id uuid,
    aggregated_balance numeric(18,2) DEFAULT 0,
    available_balance numeric(18,2) DEFAULT 0,
    held_balance numeric(18,2) DEFAULT 0,
    last_aggregated_at timestamp without time zone,
    balance_currency character varying(3),
    base_currency_balance numeric(18,2),
    fx_rate numeric(18,8),
    fx_rate_date date,
    status character varying(20) DEFAULT 'ACTIVE'::character varying,
    display_order integer DEFAULT 0,
    icon character varying(50),
    color character varying(20),
    metadata jsonb,
    tags character varying(255),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by character varying(100),
    updated_by character varying(100),
    version bigint DEFAULT 0 NOT NULL,
    legal_entity_id uuid,
    corporate_id uuid,
    CONSTRAINT chk_leaf_has_va CHECK (((((node_type)::text = 'VIRTUAL_ACCOUNT'::text) AND (is_leaf = true)) OR ((node_type)::text <> 'VIRTUAL_ACCOUNT'::text))),
    CONSTRAINT chk_master_at_level_1 CHECK (((((node_type)::text = 'MASTER'::text) AND (level_number = 1)) OR ((node_type)::text <> 'MASTER'::text))),
    CONSTRAINT hierarchy_nodes_level_number_check CHECK (((level_number >= 1) AND (level_number <= 7))),
    CONSTRAINT hierarchy_nodes_node_type_check CHECK (((node_type)::text = ANY ((ARRAY['MASTER'::character varying, 'CONSOLIDATION'::character varying, 'VIRTUAL_ACCOUNT'::character varying])::text[]))),
    CONSTRAINT hierarchy_nodes_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'INACTIVE'::character varying, 'SUSPENDED'::character varying, 'CLOSED'::character varying])::text[])))
);


ALTER TABLE public.hierarchy_nodes OWNER TO vam_user;

--
-- TOC entry 6981 (class 0 OID 0)
-- Dependencies: 255
-- Name: TABLE hierarchy_nodes; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON TABLE public.hierarchy_nodes IS 'Tree structure for virtual account hierarchy with materialized path';


--
-- TOC entry 6982 (class 0 OID 0)
-- Dependencies: 255
-- Name: COLUMN hierarchy_nodes.node_type; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.hierarchy_nodes.node_type IS 'MASTER=L1 root, CONSOLIDATION=L2-L6, VIRTUAL_ACCOUNT=L7 leaf';


--
-- TOC entry 6983 (class 0 OID 0)
-- Dependencies: 255
-- Name: COLUMN hierarchy_nodes.materialized_path; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.hierarchy_nodes.materialized_path IS 'Full path from root: /{program}/{L1}/{L2}/.../code';


--
-- TOC entry 6984 (class 0 OID 0)
-- Dependencies: 255
-- Name: COLUMN hierarchy_nodes.path_depth; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.hierarchy_nodes.path_depth IS 'Auto-calculated depth based on materialized_path';


--
-- TOC entry 6985 (class 0 OID 0)
-- Dependencies: 255
-- Name: COLUMN hierarchy_nodes.aggregated_balance; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.hierarchy_nodes.aggregated_balance IS 'Sum of all descendant virtual account balances';


--
-- TOC entry 6986 (class 0 OID 0)
-- Dependencies: 255
-- Name: COLUMN hierarchy_nodes.legal_entity_id; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.hierarchy_nodes.legal_entity_id IS 'For entity-level nodes, links to the corresponding Legal Entity for ownership tracking.';


--
-- TOC entry 6987 (class 0 OID 0)
-- Dependencies: 255
-- Name: COLUMN hierarchy_nodes.corporate_id; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.hierarchy_nodes.corporate_id IS 'Corporate that owns this hierarchy node.';


--
-- TOC entry 239 (class 1259 OID 31012)
-- Name: ihb_accounts; Type: TABLE; Schema: public; Owner: vam_user
--

CREATE TABLE public.ihb_accounts (
    id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    account_reference character varying(20) NOT NULL,
    entity_id uuid NOT NULL,
    account_type character varying(20) NOT NULL,
    currency_code character varying(3) DEFAULT 'AED'::character varying NOT NULL,
    current_balance numeric(18,2) DEFAULT 0,
    available_balance numeric(18,2) DEFAULT 0,
    interest_rate numeric(8,4) DEFAULT 0,
    accrued_interest numeric(18,2) DEFAULT 0,
    last_interest_date date,
    status character varying(20) DEFAULT 'ACTIVE'::character varying,
    opened_date date DEFAULT CURRENT_DATE,
    closed_date date,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


ALTER TABLE public.ihb_accounts OWNER TO vam_user;

--
-- TOC entry 237 (class 1259 OID 30975)
-- Name: ihb_configuration; Type: TABLE; Schema: public; Owner: vam_user
--

CREATE TABLE public.ihb_configuration (
    id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    config_key character varying(50) NOT NULL,
    config_value text,
    description text,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


ALTER TABLE public.ihb_configuration OWNER TO vam_user;

--
-- TOC entry 241 (class 1259 OID 31082)
-- Name: ihb_deposits; Type: TABLE; Schema: public; Owner: vam_user
--

CREATE TABLE public.ihb_deposits (
    id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    deposit_reference character varying(20) NOT NULL,
    depositor_entity_id uuid NOT NULL,
    depositor_entity_code character varying(20),
    principal_amount numeric(18,2) NOT NULL,
    currency_code character varying(3) DEFAULT 'AED'::character varying NOT NULL,
    current_balance numeric(18,2) NOT NULL,
    interest_rate numeric(8,4) NOT NULL,
    accrued_interest numeric(18,2) DEFAULT 0,
    total_interest_earned numeric(18,2) DEFAULT 0,
    deposit_date date NOT NULL,
    maturity_date date,
    last_interest_date date,
    deposit_type character varying(20) DEFAULT 'CALL'::character varying,
    notice_period_days integer,
    status character varying(20) DEFAULT 'ACTIVE'::character varying,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by character varying(100),
    updated_by character varying(100),
    version bigint DEFAULT 0,
    depositor_legal_entity_id uuid,
    treasury_legal_entity_id uuid,
    corporate_id uuid,
    depositor_va_id uuid,
    treasury_va_id uuid,
    interest_config_id uuid,
    sweep_execution_reference character varying(50),
    auto_created boolean DEFAULT false,
    sweep_rule_id uuid
);


ALTER TABLE public.ihb_deposits OWNER TO vam_user;

--
-- TOC entry 6988 (class 0 OID 0)
-- Dependencies: 241
-- Name: COLUMN ihb_deposits.sweep_execution_reference; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.ihb_deposits.sweep_execution_reference IS 'Reference to sweep execution that created this deposit';


--
-- TOC entry 6989 (class 0 OID 0)
-- Dependencies: 241
-- Name: COLUMN ihb_deposits.auto_created; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.ihb_deposits.auto_created IS 'True if deposit was auto-created by sweep operation';


--
-- TOC entry 6990 (class 0 OID 0)
-- Dependencies: 241
-- Name: COLUMN ihb_deposits.sweep_rule_id; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.ihb_deposits.sweep_rule_id IS 'Sweep rule ID for recurring sweep-created deposits';


--
-- TOC entry 238 (class 1259 OID 30989)
-- Name: ihb_entities; Type: TABLE; Schema: public; Owner: vam_user
--

CREATE TABLE public.ihb_entities (
    id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    entity_code character varying(20) NOT NULL,
    entity_name character varying(100) NOT NULL,
    entity_type character varying(20) DEFAULT 'SUBSIDIARY'::character varying,
    credit_limit numeric(18,2) DEFAULT 0,
    current_exposure numeric(18,2) DEFAULT 0,
    available_limit numeric(18,2) DEFAULT 0,
    lending_rate_spread numeric(8,4) DEFAULT 0,
    borrowing_rate_spread numeric(8,4) DEFAULT 0,
    contact_name character varying(100),
    contact_email character varying(255),
    status character varying(20) DEFAULT 'ACTIVE'::character varying,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    version bigint DEFAULT 0,
    created_by character varying(100),
    updated_by character varying(100)
);


ALTER TABLE public.ihb_entities OWNER TO vam_user;

--
-- TOC entry 248 (class 1259 OID 32768)
-- Name: ihb_interest_accruals; Type: TABLE; Schema: public; Owner: vam_user
--

CREATE TABLE public.ihb_interest_accruals (
    id uuid NOT NULL,
    accrued_amount numeric(18,2),
    average_balance numeric(18,2),
    calculated_at timestamp(6) without time zone,
    created_by character varying(100),
    currency_code character varying(3),
    entity_code character varying(20),
    interest_rate numeric(8,4),
    period_end date NOT NULL,
    period_start date NOT NULL,
    position_type character varying(20),
    posted_at timestamp(6) without time zone,
    posted_date date,
    posting_reference character varying(30),
    status character varying(20),
    entity_id uuid NOT NULL,
    CONSTRAINT ihb_interest_accruals_position_type_check CHECK (((position_type)::text = ANY ((ARRAY['SURPLUS'::character varying, 'DEFICIT'::character varying])::text[]))),
    CONSTRAINT ihb_interest_accruals_status_check CHECK (((status)::text = ANY ((ARRAY['ACCRUED'::character varying, 'POSTED'::character varying, 'SETTLED'::character varying, 'REVERSED'::character varying])::text[])))
);


ALTER TABLE public.ihb_interest_accruals OWNER TO vam_user;

--
-- TOC entry 240 (class 1259 OID 31042)
-- Name: ihb_loans; Type: TABLE; Schema: public; Owner: vam_user
--

CREATE TABLE public.ihb_loans (
    id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    loan_reference character varying(20) NOT NULL,
    lender_entity_id uuid NOT NULL,
    lender_entity_code character varying(20),
    borrower_entity_id uuid NOT NULL,
    borrower_entity_code character varying(20),
    principal_amount numeric(18,2) NOT NULL,
    currency_code character varying(3) DEFAULT 'AED'::character varying NOT NULL,
    outstanding_amount numeric(18,2) NOT NULL,
    interest_rate numeric(8,4) NOT NULL,
    interest_type character varying(20) DEFAULT 'FIXED'::character varying,
    base_rate_type character varying(20),
    spread numeric(8,4),
    accrued_interest numeric(18,2) DEFAULT 0,
    total_interest_paid numeric(18,2) DEFAULT 0,
    disbursement_date date NOT NULL,
    maturity_date date NOT NULL,
    next_interest_date date,
    next_payment_date date,
    repayment_frequency character varying(20) DEFAULT 'MONTHLY'::character varying,
    status character varying(20) DEFAULT 'ACTIVE'::character varying,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by character varying(100),
    updated_by character varying(100),
    version bigint DEFAULT 0,
    lender_legal_entity_id uuid,
    borrower_legal_entity_id uuid,
    corporate_id uuid,
    lender_va_id uuid,
    borrower_va_id uuid,
    interest_config_id uuid
);


ALTER TABLE public.ihb_loans OWNER TO vam_user;

--
-- TOC entry 242 (class 1259 OID 31113)
-- Name: ihb_transactions; Type: TABLE; Schema: public; Owner: vam_user
--

CREATE TABLE public.ihb_transactions (
    id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    transaction_reference character varying(30) NOT NULL,
    related_type character varying(20) NOT NULL,
    related_id uuid,
    debit_entity_id uuid,
    debit_entity_code character varying(20),
    credit_entity_id uuid,
    credit_entity_code character varying(20),
    amount numeric(18,2) NOT NULL,
    currency_code character varying(3) DEFAULT 'AED'::character varying NOT NULL,
    transaction_type character varying(20) NOT NULL,
    description text,
    value_date date DEFAULT CURRENT_DATE,
    status character varying(20) DEFAULT 'COMPLETED'::character varying,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by character varying(100),
    from_entity_code character varying(20),
    interest_rate numeric(8,4),
    source_id uuid,
    source_type character varying(20),
    to_entity_code character varying(20),
    transaction_date date NOT NULL,
    transaction_ref character varying(30) NOT NULL,
    from_entity_id uuid,
    to_entity_id uuid
);


ALTER TABLE public.ihb_transactions OWNER TO vam_user;

--
-- TOC entry 244 (class 1259 OID 31165)
-- Name: integration_connections; Type: TABLE; Schema: public; Owner: vam_user
--

CREATE TABLE public.integration_connections (
    id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    connector_id uuid NOT NULL,
    connection_name character varying(100) NOT NULL,
    environment character varying(20) DEFAULT 'SANDBOX'::character varying NOT NULL,
    credentials jsonb,
    sync_frequency character varying(20) DEFAULT 'DAILY'::character varying,
    last_sync_at timestamp without time zone,
    next_sync_at timestamp without time zone,
    status character varying(20) DEFAULT 'DISCONNECTED'::character varying,
    error_message text,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by character varying(100),
    updated_by character varying(100),
    version bigint DEFAULT 0
);


ALTER TABLE public.integration_connections OWNER TO vam_user;

--
-- TOC entry 243 (class 1259 OID 31147)
-- Name: integration_connectors; Type: TABLE; Schema: public; Owner: vam_user
--

CREATE TABLE public.integration_connectors (
    id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    connector_code character varying(50) NOT NULL,
    connector_name character varying(100) NOT NULL,
    category character varying(30) NOT NULL,
    auth_type character varying(30) NOT NULL,
    base_url_template character varying(500),
    supported_features jsonb,
    status character varying(20) DEFAULT 'AVAILABLE'::character varying,
    documentation_url character varying(500),
    logo_url character varying(500),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by character varying(100),
    updated_by character varying(100),
    version bigint DEFAULT 0,
    short_name character varying(30),
    description text,
    required_credentials jsonb,
    icon_type character varying(50),
    region character varying(50),
    compliance_standards jsonb,
    sort_order integer DEFAULT 0,
    is_beta boolean DEFAULT false
);


ALTER TABLE public.integration_connectors OWNER TO vam_user;

--
-- TOC entry 245 (class 1259 OID 31189)
-- Name: integration_data_flows; Type: TABLE; Schema: public; Owner: vam_user
--

CREATE TABLE public.integration_data_flows (
    id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    connection_id uuid NOT NULL,
    flow_name character varying(100) NOT NULL,
    direction character varying(20) NOT NULL,
    source_entity character varying(100) NOT NULL,
    target_entity character varying(100) NOT NULL,
    schedule_enabled boolean DEFAULT true,
    last_run_at timestamp without time zone,
    records_processed integer DEFAULT 0,
    status character varying(20) DEFAULT 'ACTIVE'::character varying,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by character varying(100),
    updated_by character varying(100),
    version bigint DEFAULT 0
);


ALTER TABLE public.integration_data_flows OWNER TO vam_user;

--
-- TOC entry 246 (class 1259 OID 31213)
-- Name: integration_field_mappings; Type: TABLE; Schema: public; Owner: vam_user
--

CREATE TABLE public.integration_field_mappings (
    id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    flow_id uuid NOT NULL,
    source_field character varying(100) NOT NULL,
    target_field character varying(100) NOT NULL,
    transformation character varying(50) DEFAULT 'DIRECT'::character varying,
    transformation_params jsonb,
    is_required boolean DEFAULT false,
    default_value character varying(255),
    sort_order integer DEFAULT 0,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


ALTER TABLE public.integration_field_mappings OWNER TO vam_user;

--
-- TOC entry 247 (class 1259 OID 31235)
-- Name: integration_sync_logs; Type: TABLE; Schema: public; Owner: vam_user
--

CREATE TABLE public.integration_sync_logs (
    id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    connection_id uuid NOT NULL,
    flow_id uuid,
    start_time timestamp without time zone NOT NULL,
    end_time timestamp without time zone,
    records_processed integer DEFAULT 0,
    records_failed integer DEFAULT 0,
    status character varying(20) NOT NULL,
    error_message text,
    error_details jsonb,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by character varying(100),
    updated_by character varying(100),
    version bigint DEFAULT 0
);


ALTER TABLE public.integration_sync_logs OWNER TO vam_user;

--
-- TOC entry 277 (class 1259 OID 33957)
-- Name: intercompany_recharges; Type: TABLE; Schema: public; Owner: vam_user
--

CREATE TABLE public.intercompany_recharges (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    recharge_reference character varying(50) NOT NULL,
    payer_entity_id uuid NOT NULL,
    payer_entity_code character varying(50) NOT NULL,
    payer_entity_name character varying(200),
    payer_va_id uuid,
    behalf_entity_id uuid NOT NULL,
    behalf_entity_code character varying(50) NOT NULL,
    behalf_entity_name character varying(200),
    behalf_va_id uuid,
    original_payable_id uuid NOT NULL,
    original_payment_execution_id uuid,
    original_payment_reference character varying(100),
    original_amount numeric(18,2) NOT NULL,
    recharge_amount numeric(18,2) NOT NULL,
    currency_code character varying(3) DEFAULT 'AED'::character varying NOT NULL,
    service_fee numeric(18,2) DEFAULT 0,
    admin_fee numeric(18,2) DEFAULT 0,
    fx_markup numeric(18,2) DEFAULT 0,
    total_recharge numeric(18,2) NOT NULL,
    transfer_pricing_rate numeric(8,4),
    arm_length_validated boolean DEFAULT false,
    arm_length_notes text,
    ihb_transaction_id uuid,
    ihb_loan_id uuid,
    creates_intercompany_loan boolean DEFAULT false,
    status character varying(30) DEFAULT 'PENDING'::character varying,
    settlement_date date,
    settlement_reference character varying(100),
    settled_via character varying(30),
    approval_required boolean DEFAULT true,
    approved_by character varying(100),
    approved_at timestamp without time zone,
    rejection_reason character varying(500),
    created_by character varying(100),
    updated_by character varying(100),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    version bigint
);


ALTER TABLE public.intercompany_recharges OWNER TO vam_user;

--
-- TOC entry 6991 (class 0 OID 0)
-- Dependencies: 277
-- Name: TABLE intercompany_recharges; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON TABLE public.intercompany_recharges IS 'POBO intercompany recharge records';


--
-- TOC entry 280 (class 1259 OID 34056)
-- Name: intercompany_transactions; Type: TABLE; Schema: public; Owner: vam_user
--

CREATE TABLE public.intercompany_transactions (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone,
    created_by character varying(255),
    updated_at timestamp(6) without time zone,
    updated_by character varying(255),
    version bigint,
    accrued_interest numeric(19,4),
    amount numeric(19,4) NOT NULL,
    behalf_entity_code character varying(20) NOT NULL,
    behalf_entity_id uuid NOT NULL,
    behalf_entity_name character varying(100) NOT NULL,
    charges numeric(19,4),
    currency_code character varying(3) NOT NULL,
    description character varying(500),
    ihb_deposit_id uuid,
    ihb_interest_rate numeric(8,4),
    ihb_loan_id uuid,
    net_amount numeric(19,4),
    original_reference character varying(100),
    original_reference_id uuid,
    original_reference_type character varying(20),
    paying_entity_code character varying(20) NOT NULL,
    paying_entity_id uuid NOT NULL,
    paying_entity_name character varying(100) NOT NULL,
    processed_at timestamp(6) without time zone,
    processed_by character varying(100),
    settled_at timestamp(6) without time zone,
    settled_by character varying(100),
    settlement_ref character varying(50),
    status character varying(20) NOT NULL,
    status_reason character varying(255),
    transaction_ref character varying(50) NOT NULL,
    transaction_type character varying(20) NOT NULL,
    value_date timestamp(6) without time zone,
    viban character varying(34),
    viban_id uuid,
    CONSTRAINT intercompany_transactions_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'ACTIVE'::character varying, 'PROCESSED'::character varying, 'COMPLETED'::character varying, 'SETTLED'::character varying, 'REVERSED'::character varying, 'FAILED'::character varying])::text[]))),
    CONSTRAINT intercompany_transactions_transaction_type_check CHECK (((transaction_type)::text = ANY ((ARRAY['POBO'::character varying, 'POBO_PAYMENT'::character varying, 'COBO'::character varying, 'COBO_COLLECTION'::character varying, 'SETTLEMENT'::character varying, 'INTEREST'::character varying, 'ADJUSTMENT'::character varying])::text[])))
);


ALTER TABLE public.intercompany_transactions OWNER TO vam_user;

--
-- TOC entry 291 (class 1259 OID 50773)
-- Name: interest_configurations; Type: TABLE; Schema: public; Owner: vam_user
--

CREATE TABLE public.interest_configurations (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    corporate_id uuid NOT NULL,
    config_name character varying(100),
    external_reference character varying(50),
    config_type character varying(20) NOT NULL,
    target_id uuid,
    target_type character varying(30),
    currency_code character varying(3) NOT NULL,
    credit_base_rate_type character varying(20),
    credit_base_rate numeric(8,5),
    credit_spread numeric(8,5),
    effective_credit_rate numeric(8,5),
    credit_min_balance numeric(19,4),
    debit_base_rate_type character varying(20),
    debit_base_rate numeric(8,5),
    debit_spread numeric(8,5),
    effective_debit_rate numeric(8,5),
    penalty_rate numeric(8,5),
    day_count_convention character varying(20) DEFAULT 'ACT/360'::character varying,
    compounding_frequency character varying(20) DEFAULT 'DAILY'::character varying,
    calculation_frequency character varying(20) DEFAULT 'DAILY'::character varying,
    posting_frequency character varying(20) DEFAULT 'MONTHLY'::character varying,
    is_tiered boolean DEFAULT false,
    tier_config text,
    effective_from date,
    effective_to date,
    status character varying(20) DEFAULT 'ACTIVE'::character varying,
    last_sync_at timestamp without time zone,
    source_system character varying(50),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by character varying(100),
    updated_by character varying(100),
    version bigint DEFAULT 0,
    CONSTRAINT chk_ic_config_type CHECK (((config_type)::text = ANY ((ARRAY['EXTERNAL'::character varying, 'INTERNAL'::character varying])::text[]))),
    CONSTRAINT chk_ic_status CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'PENDING_APPROVAL'::character varying, 'ACTIVE'::character varying, 'SUSPENDED'::character varying, 'EXPIRED'::character varying, 'CANCELLED'::character varying])::text[]))),
    CONSTRAINT chk_ic_target_type CHECK (((target_type IS NULL) OR ((target_type)::text = ANY ((ARRAY['VIRTUAL_ACCOUNT'::character varying, 'PHYSICAL_ACCOUNT'::character varying, 'LEGAL_ENTITY'::character varying, 'PROGRAM'::character varying, 'CURRENCY'::character varying, 'CORPORATE'::character varying])::text[]))))
);


ALTER TABLE public.interest_configurations OWNER TO vam_user;

--
-- TOC entry 6992 (class 0 OID 0)
-- Dependencies: 291
-- Name: TABLE interest_configurations; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON TABLE public.interest_configurations IS 'Interest rate settings for accounts';


--
-- TOC entry 6993 (class 0 OID 0)
-- Dependencies: 291
-- Name: COLUMN interest_configurations.config_type; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.interest_configurations.config_type IS 'EXTERNAL (bank rates) or INTERNAL (treasury rates)';


--
-- TOC entry 6994 (class 0 OID 0)
-- Dependencies: 291
-- Name: COLUMN interest_configurations.effective_credit_rate; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.interest_configurations.effective_credit_rate IS 'Effective rate for positive balances';


--
-- TOC entry 6995 (class 0 OID 0)
-- Dependencies: 291
-- Name: COLUMN interest_configurations.effective_debit_rate; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.interest_configurations.effective_debit_rate IS 'Effective rate for overdrafts';


--
-- TOC entry 286 (class 1259 OID 50497)
-- Name: legal_entities; Type: TABLE; Schema: public; Owner: vam_user
--

CREATE TABLE public.legal_entities (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    entity_code character varying(20) NOT NULL,
    entity_name character varying(200) NOT NULL,
    short_name character varying(50),
    parent_entity_id uuid,
    hierarchy_path character varying(500),
    hierarchy_level integer DEFAULT 0,
    ownership_percent numeric(5,2) DEFAULT 100.00,
    consolidation_method character varying(20) DEFAULT 'FULL'::character varying,
    entity_type character varying(30) DEFAULT 'SUBSIDIARY'::character varying NOT NULL,
    legal_form character varying(50),
    country_code character varying(2),
    jurisdiction character varying(100),
    tax_id character varying(50),
    registration_number character varying(50),
    functional_currency character varying(3) NOT NULL,
    reporting_currency character varying(3),
    is_treasury_center boolean DEFAULT false,
    can_hold_physical_accounts boolean DEFAULT true,
    can_participate_pooling boolean DEFAULT true,
    can_participate_netting boolean DEFAULT true,
    internal_credit_limit numeric(19,4),
    internal_limit_utilized numeric(19,4) DEFAULT 0,
    internal_limit_currency character varying(3),
    limit_warning_threshold numeric(5,2) DEFAULT 80.00,
    status character varying(20) DEFAULT 'ACTIVE'::character varying,
    effective_from date,
    effective_to date,
    corporate_id uuid NOT NULL,
    contact_email character varying(100),
    contact_phone character varying(30),
    registered_address character varying(500),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by character varying(100),
    updated_by character varying(100),
    version bigint DEFAULT 0,
    is_bank_customer boolean DEFAULT false,
    bancs_customer_id character varying(50),
    hierarchy_node_id uuid,
    program_id uuid,
    currency_mirrors_initialized boolean DEFAULT false,
    operating_currencies jsonb,
    ihb_enabled boolean DEFAULT false,
    ihb_credit_limit numeric(19,4),
    ihb_current_exposure numeric(19,4) DEFAULT 0,
    ihb_available_limit numeric(19,4),
    lending_rate_spread numeric(8,5) DEFAULT 0.50000,
    borrowing_rate_spread numeric(8,5) DEFAULT 0.75000,
    can_lend boolean DEFAULT false,
    can_borrow boolean DEFAULT true,
    settlement_va_id uuid,
    ihb_currency character varying(3),
    total_lent_out numeric(19,4) DEFAULT 0,
    total_deposited numeric(19,4) DEFAULT 0,
    net_ihb_position numeric(19,4) DEFAULT 0,
    ihb_interest_config_id uuid,
    CONSTRAINT chk_le_consolidation CHECK (((consolidation_method)::text = ANY ((ARRAY['FULL'::character varying, 'PROPORTIONAL'::character varying, 'EQUITY'::character varying, 'NONE'::character varying])::text[]))),
    CONSTRAINT chk_le_entity_type CHECK (((entity_type)::text = ANY ((ARRAY['HOLDING'::character varying, 'SUBSIDIARY'::character varying, 'BRANCH'::character varying, 'REPRESENTATIVE'::character varying, 'JOINT_VENTURE'::character varying, 'ASSOCIATE'::character varying, 'SPV'::character varying, 'TREASURY_CENTER'::character varying])::text[]))),
    CONSTRAINT chk_le_ownership_percent CHECK (((ownership_percent >= (0)::numeric) AND (ownership_percent <= (100)::numeric))),
    CONSTRAINT chk_le_status CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'INACTIVE'::character varying, 'SUSPENDED'::character varying, 'PENDING_APPROVAL'::character varying, 'CLOSED'::character varying])::text[])))
);


ALTER TABLE public.legal_entities OWNER TO vam_user;

--
-- TOC entry 6996 (class 0 OID 0)
-- Dependencies: 286
-- Name: TABLE legal_entities; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON TABLE public.legal_entities IS 'Corporate subsidiaries, branches, and legal structures';


--
-- TOC entry 6997 (class 0 OID 0)
-- Dependencies: 286
-- Name: COLUMN legal_entities.entity_code; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.legal_entities.entity_code IS 'Unique entity code (e.g., ACME-UK)';


--
-- TOC entry 6998 (class 0 OID 0)
-- Dependencies: 286
-- Name: COLUMN legal_entities.parent_entity_id; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.legal_entities.parent_entity_id IS 'Parent entity (null for holding company)';


--
-- TOC entry 6999 (class 0 OID 0)
-- Dependencies: 286
-- Name: COLUMN legal_entities.hierarchy_path; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.legal_entities.hierarchy_path IS 'Materialized path for tree queries';


--
-- TOC entry 7000 (class 0 OID 0)
-- Dependencies: 286
-- Name: COLUMN legal_entities.ownership_percent; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.legal_entities.ownership_percent IS 'Ownership percentage by parent (0-100)';


--
-- TOC entry 7001 (class 0 OID 0)
-- Dependencies: 286
-- Name: COLUMN legal_entities.consolidation_method; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.legal_entities.consolidation_method IS 'FULL, PROPORTIONAL, EQUITY, or NONE';


--
-- TOC entry 7002 (class 0 OID 0)
-- Dependencies: 286
-- Name: COLUMN legal_entities.entity_type; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.legal_entities.entity_type IS 'HOLDING, SUBSIDIARY, BRANCH, etc.';


--
-- TOC entry 7003 (class 0 OID 0)
-- Dependencies: 286
-- Name: COLUMN legal_entities.is_treasury_center; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.legal_entities.is_treasury_center IS 'Whether entity acts as treasury center';


--
-- TOC entry 7004 (class 0 OID 0)
-- Dependencies: 286
-- Name: COLUMN legal_entities.internal_credit_limit; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.legal_entities.internal_credit_limit IS 'Internal limit allocated by CFO';


--
-- TOC entry 7005 (class 0 OID 0)
-- Dependencies: 286
-- Name: COLUMN legal_entities.is_bank_customer; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.legal_entities.is_bank_customer IS 'Whether this entity is a customer of the bank (can have EXTERNAL limits)';


--
-- TOC entry 7006 (class 0 OID 0)
-- Dependencies: 286
-- Name: COLUMN legal_entities.bancs_customer_id; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.legal_entities.bancs_customer_id IS 'BANCS Customer ID (CIF) linking to Core Banking System';


--
-- TOC entry 7007 (class 0 OID 0)
-- Dependencies: 286
-- Name: COLUMN legal_entities.hierarchy_node_id; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.legal_entities.hierarchy_node_id IS 'Links legal entity to its node in the VA hierarchy tree for Currency Mirror placement.';


--
-- TOC entry 7008 (class 0 OID 0)
-- Dependencies: 286
-- Name: COLUMN legal_entities.program_id; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.legal_entities.program_id IS 'Program this legal entity participates in for Currency Mirror auto-creation.';


--
-- TOC entry 7009 (class 0 OID 0)
-- Dependencies: 286
-- Name: COLUMN legal_entities.currency_mirrors_initialized; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.legal_entities.currency_mirrors_initialized IS 'Whether Currency Mirrors have been initialized for this entity.';


--
-- TOC entry 7010 (class 0 OID 0)
-- Dependencies: 286
-- Name: COLUMN legal_entities.operating_currencies; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.legal_entities.operating_currencies IS 'JSON array of operating currencies for this entity, e.g. ["EUR", "USD", "GBP"].';


--
-- TOC entry 7011 (class 0 OID 0)
-- Dependencies: 286
-- Name: COLUMN legal_entities.ihb_enabled; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.legal_entities.ihb_enabled IS 'Whether this entity participates in In-House Banking operations';


--
-- TOC entry 7012 (class 0 OID 0)
-- Dependencies: 286
-- Name: COLUMN legal_entities.ihb_credit_limit; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.legal_entities.ihb_credit_limit IS 'Maximum amount this entity can borrow through IHB. Set by Treasury/CFO.';


--
-- TOC entry 7013 (class 0 OID 0)
-- Dependencies: 286
-- Name: COLUMN legal_entities.ihb_current_exposure; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.legal_entities.ihb_current_exposure IS 'Current total borrowed amount via IHB';


--
-- TOC entry 7014 (class 0 OID 0)
-- Dependencies: 286
-- Name: COLUMN legal_entities.ihb_available_limit; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.legal_entities.ihb_available_limit IS 'Available IHB borrowing limit';


--
-- TOC entry 7015 (class 0 OID 0)
-- Dependencies: 286
-- Name: COLUMN legal_entities.lending_rate_spread; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.legal_entities.lending_rate_spread IS 'Spread added to base rate when lending';


--
-- TOC entry 7016 (class 0 OID 0)
-- Dependencies: 286
-- Name: COLUMN legal_entities.borrowing_rate_spread; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.legal_entities.borrowing_rate_spread IS 'Spread added to base rate when borrowing';


--
-- TOC entry 7017 (class 0 OID 0)
-- Dependencies: 286
-- Name: COLUMN legal_entities.can_lend; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.legal_entities.can_lend IS 'Whether this entity can act as a lender in IHB transactions. Usually TRUE for Treasury Centers.';


--
-- TOC entry 7018 (class 0 OID 0)
-- Dependencies: 286
-- Name: COLUMN legal_entities.can_borrow; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.legal_entities.can_borrow IS 'Whether this entity can act as a borrower in IHB transactions. Usually TRUE for subsidiaries.';


--
-- TOC entry 7019 (class 0 OID 0)
-- Dependencies: 286
-- Name: COLUMN legal_entities.settlement_va_id; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.legal_entities.settlement_va_id IS 'Virtual Account used for IHB transaction settlements. Auto-resolved if not set.';


--
-- TOC entry 7020 (class 0 OID 0)
-- Dependencies: 286
-- Name: COLUMN legal_entities.ihb_currency; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.legal_entities.ihb_currency IS 'Primary currency for IHB operations (defaults to functional_currency)';


--
-- TOC entry 7021 (class 0 OID 0)
-- Dependencies: 286
-- Name: COLUMN legal_entities.total_lent_out; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.legal_entities.total_lent_out IS 'Total amount currently lent to other entities';


--
-- TOC entry 7022 (class 0 OID 0)
-- Dependencies: 286
-- Name: COLUMN legal_entities.total_deposited; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.legal_entities.total_deposited IS 'Total deposits placed with treasury';


--
-- TOC entry 7023 (class 0 OID 0)
-- Dependencies: 286
-- Name: COLUMN legal_entities.net_ihb_position; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.legal_entities.net_ihb_position IS 'Net IHB position: lent + deposited - borrowed';


--
-- TOC entry 7024 (class 0 OID 0)
-- Dependencies: 286
-- Name: COLUMN legal_entities.ihb_interest_config_id; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.legal_entities.ihb_interest_config_id IS 'Reference to InterestConfiguration for IHB lending/borrowing rates. Only set for Treasury Center entities.';


--
-- TOC entry 234 (class 1259 OID 30899)
-- Name: netting_cycles; Type: TABLE; Schema: public; Owner: vam_user
--

CREATE TABLE public.netting_cycles (
    id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    cycle_reference character varying(20) NOT NULL,
    cycle_name character varying(100) NOT NULL,
    period_start date NOT NULL,
    period_end date NOT NULL,
    settlement_date date,
    base_currency character varying(3) DEFAULT 'AED'::character varying NOT NULL,
    total_gross numeric(18,2) DEFAULT 0,
    total_net numeric(18,2) DEFAULT 0,
    savings_amount numeric(18,2) DEFAULT 0,
    savings_percent numeric(8,4) DEFAULT 0,
    entry_count integer DEFAULT 0,
    participant_count integer DEFAULT 0,
    status character varying(20) DEFAULT 'DRAFT'::character varying,
    approved_by character varying(100),
    approved_at timestamp without time zone,
    settled_at timestamp without time zone,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by character varying(100),
    updated_by character varying(100),
    version bigint DEFAULT 0
);


ALTER TABLE public.netting_cycles OWNER TO vam_user;

--
-- TOC entry 235 (class 1259 OID 30925)
-- Name: netting_entries; Type: TABLE; Schema: public; Owner: vam_user
--

CREATE TABLE public.netting_entries (
    id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    cycle_id uuid NOT NULL,
    entry_reference character varying(30) NOT NULL,
    payer_entity_id uuid NOT NULL,
    payer_entity_code character varying(20) NOT NULL,
    payer_entity_name character varying(100),
    payee_entity_id uuid NOT NULL,
    payee_entity_code character varying(20) NOT NULL,
    payee_entity_name character varying(100),
    gross_amount numeric(18,2) NOT NULL,
    currency_code character varying(3) NOT NULL,
    exchange_rate numeric(18,8) DEFAULT 1,
    base_amount numeric(18,2) NOT NULL,
    source_type character varying(20),
    source_reference character varying(50),
    status character varying(20) DEFAULT 'PENDING'::character varying,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by character varying(100),
    updated_by character varying(100),
    version bigint DEFAULT 0
);


ALTER TABLE public.netting_entries OWNER TO vam_user;

--
-- TOC entry 236 (class 1259 OID 30952)
-- Name: netting_settlements; Type: TABLE; Schema: public; Owner: vam_user
--

CREATE TABLE public.netting_settlements (
    id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    cycle_id uuid NOT NULL,
    entity_id uuid NOT NULL,
    entity_code character varying(20) NOT NULL,
    entity_name character varying(100),
    total_payable numeric(18,2) DEFAULT 0,
    total_receivable numeric(18,2) DEFAULT 0,
    net_position numeric(18,2) DEFAULT 0,
    settlement_direction character varying(10),
    settlement_account character varying(34),
    settlement_status character varying(20) DEFAULT 'PENDING'::character varying,
    settled_at timestamp without time zone,
    settlement_reference character varying(50),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by character varying(100),
    updated_by character varying(100),
    version bigint DEFAULT 0
);


ALTER TABLE public.netting_settlements OWNER TO vam_user;

--
-- TOC entry 270 (class 1259 OID 33752)
-- Name: notification_preferences; Type: TABLE; Schema: public; Owner: vam_user
--

CREATE TABLE public.notification_preferences (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    corporate_id uuid,
    user_id character varying(100),
    notification_type character varying(50) NOT NULL,
    in_app_enabled boolean DEFAULT true,
    email_enabled boolean DEFAULT true,
    sms_enabled boolean DEFAULT false,
    push_enabled boolean DEFAULT false,
    webhook_enabled boolean DEFAULT false,
    digest_mode character varying(20) DEFAULT 'IMMEDIATE'::character varying,
    quiet_hours_start time without time zone,
    quiet_hours_end time without time zone,
    threshold_amount numeric(18,2),
    threshold_count integer,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


ALTER TABLE public.notification_preferences OWNER TO vam_user;

--
-- TOC entry 7025 (class 0 OID 0)
-- Dependencies: 270
-- Name: TABLE notification_preferences; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON TABLE public.notification_preferences IS 'User notification preferences';


--
-- TOC entry 269 (class 1259 OID 33722)
-- Name: notifications; Type: TABLE; Schema: public; Owner: vam_user
--

CREATE TABLE public.notifications (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    corporate_id uuid,
    user_id character varying(100),
    recipient_email character varying(200),
    recipient_mobile character varying(30),
    notification_type character varying(50) NOT NULL,
    category character varying(30) DEFAULT 'INFO'::character varying,
    priority character varying(20) DEFAULT 'NORMAL'::character varying,
    title character varying(200) NOT NULL,
    message text NOT NULL,
    summary character varying(500),
    reference_type character varying(50),
    reference_id uuid,
    reference_number character varying(100),
    channel character varying(20) DEFAULT 'IN_APP'::character varying NOT NULL,
    delivery_status character varying(20) DEFAULT 'PENDING'::character varying,
    sent_at timestamp without time zone,
    delivered_at timestamp without time zone,
    read_at timestamp without time zone,
    retry_count integer DEFAULT 0,
    last_retry_at timestamp without time zone,
    error_message character varying(500),
    expires_at timestamp without time zone,
    metadata jsonb,
    action_url character varying(500),
    action_label character varying(100),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by character varying(255),
    updated_at timestamp(6) without time zone,
    updated_by character varying(255),
    version bigint
);


ALTER TABLE public.notifications OWNER TO vam_user;

--
-- TOC entry 7026 (class 0 OID 0)
-- Dependencies: 269
-- Name: TABLE notifications; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON TABLE public.notifications IS 'Event-driven notification records';


--
-- TOC entry 231 (class 1259 OID 30826)
-- Name: notional_pools; Type: TABLE; Schema: public; Owner: vam_user
--

CREATE TABLE public.notional_pools (
    id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    pool_reference character varying(20) NOT NULL,
    pool_name character varying(100) NOT NULL,
    pool_currency character varying(3) DEFAULT 'AED'::character varying NOT NULL,
    target_balance numeric(18,2) DEFAULT 0,
    interest_rate numeric(8,4) DEFAULT 0,
    interest_calculation_method character varying(20) DEFAULT 'DAILY_AVERAGE'::character varying,
    total_balance numeric(18,2) DEFAULT 0,
    interest_savings_ytd numeric(18,2) DEFAULT 0,
    member_count integer DEFAULT 0,
    status character varying(20) DEFAULT 'ACTIVE'::character varying,
    effective_from date DEFAULT CURRENT_DATE,
    effective_to date,
    last_calculation_date date,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by character varying(100),
    updated_by character varying(100),
    version bigint DEFAULT 0
);


ALTER TABLE public.notional_pools OWNER TO vam_user;

--
-- TOC entry 249 (class 1259 OID 32784)
-- Name: parties; Type: TABLE; Schema: public; Owner: vam_user
--

CREATE TABLE public.parties (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone,
    created_by character varying(255),
    updated_at timestamp(6) without time zone,
    updated_by character varying(255),
    version bigint,
    address_line1 character varying(255),
    address_line2 character varying(255),
    adverse_media_status boolean,
    city character varying(255),
    contact_email character varying(255),
    contact_name character varying(255),
    contact_phone character varying(30),
    corporate_id uuid NOT NULL,
    country character varying(2) NOT NULL,
    department character varying(255),
    display_name character varying(255),
    ecommerce_enabled boolean,
    ecommerce_platforms character varying(255),
    employee_id character varying(30),
    kyc_expires_at date,
    kyc_status character varying(20) NOT NULL,
    kyc_verified_at timestamp(6) without time zone,
    kyc_verified_by character varying(255),
    last_transaction_at timestamp(6) without time zone,
    legal_name character varying(255) NOT NULL,
    onboarded_at timestamp(6) without time zone,
    party_code character varying(30) NOT NULL,
    party_type character varying(30) NOT NULL,
    pep_status boolean,
    postal_code character varying(20),
    registration_country character varying(2) NOT NULL,
    registration_number character varying(50),
    risk_rating character varying(20) NOT NULL,
    risk_score integer,
    roles character varying(255) NOT NULL,
    sanctions_last_checked timestamp(6) without time zone,
    sanctions_status character varying(20),
    state character varying(255),
    status character varying(20) NOT NULL,
    tax_id character varying(50),
    trade_name character varying(255),
    owning_entity_id uuid,
    owning_entity_code character varying(20),
    pobo_eligible boolean DEFAULT false,
    pobo_default_payer_entity_id uuid,
    pobo_default_payer_entity_code character varying(20),
    is_intercompany boolean DEFAULT false,
    linked_legal_entity_id uuid,
    linked_legal_entity_code character varying(20),
    netting_eligible boolean DEFAULT false,
    ic_settlement_method character varying(30) DEFAULT 'NETTING'::character varying,
    ic_credit_limit numeric(19,4),
    ic_current_exposure numeric(19,4) DEFAULT 0,
    ic_currency character varying(3),
    CONSTRAINT parties_kyc_status_check CHECK (((kyc_status)::text = ANY ((ARRAY['PENDING'::character varying, 'IN_PROGRESS'::character varying, 'VERIFIED'::character varying, 'EXPIRED'::character varying, 'REJECTED'::character varying, 'EXEMPTED'::character varying])::text[]))),
    CONSTRAINT parties_party_type_check CHECK (((party_type)::text = ANY ((ARRAY['INDIVIDUAL'::character varying, 'COMPANY'::character varying, 'GOVERNMENT'::character varying, 'FINANCIAL_INSTITUTION'::character varying])::text[]))),
    CONSTRAINT parties_risk_rating_check CHECK (((risk_rating)::text = ANY ((ARRAY['LOW'::character varying, 'MEDIUM'::character varying, 'HIGH'::character varying, 'PROHIBITED'::character varying])::text[]))),
    CONSTRAINT parties_sanctions_status_check CHECK (((sanctions_status)::text = ANY ((ARRAY['CLEAR'::character varying, 'POTENTIAL_MATCH'::character varying, 'FALSE_POSITIVE'::character varying, 'CONFIRMED_MATCH'::character varying])::text[]))),
    CONSTRAINT parties_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'SUSPENDED'::character varying, 'BLOCKED'::character varying, 'INACTIVE'::character varying])::text[])))
);


ALTER TABLE public.parties OWNER TO vam_user;

--
-- TOC entry 7027 (class 0 OID 0)
-- Dependencies: 249
-- Name: COLUMN parties.owning_entity_id; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.parties.owning_entity_id IS 'The legal entity (subsidiary) that owns this party relationship. Enables multi-entity corporate structures and POBO routing.';


--
-- TOC entry 7028 (class 0 OID 0)
-- Dependencies: 249
-- Name: COLUMN parties.owning_entity_code; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.parties.owning_entity_code IS 'Denormalized code of owning entity for display/query performance.';


--
-- TOC entry 7029 (class 0 OID 0)
-- Dependencies: 249
-- Name: COLUMN parties.pobo_eligible; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.parties.pobo_eligible IS 'Whether this party (vendor/customer) is eligible for POBO/COBO. Treasury must approve.';


--
-- TOC entry 7030 (class 0 OID 0)
-- Dependencies: 249
-- Name: COLUMN parties.pobo_default_payer_entity_id; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.parties.pobo_default_payer_entity_id IS 'Default paying entity for POBO payments to this vendor. Usually corporate HQ treasury.';


--
-- TOC entry 7031 (class 0 OID 0)
-- Dependencies: 249
-- Name: COLUMN parties.pobo_default_payer_entity_code; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.parties.pobo_default_payer_entity_code IS 'Denormalized code of default POBO payer for display.';


--
-- TOC entry 7032 (class 0 OID 0)
-- Dependencies: 249
-- Name: COLUMN parties.is_intercompany; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.parties.is_intercompany IS 'True if this party represents another legal entity within the corporate group.';


--
-- TOC entry 7033 (class 0 OID 0)
-- Dependencies: 249
-- Name: COLUMN parties.linked_legal_entity_id; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.parties.linked_legal_entity_id IS 'If intercompany, links to the actual legal entity this party represents.';


--
-- TOC entry 7034 (class 0 OID 0)
-- Dependencies: 249
-- Name: COLUMN parties.linked_legal_entity_code; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.parties.linked_legal_entity_code IS 'Denormalized code of linked legal entity for display.';


--
-- TOC entry 7035 (class 0 OID 0)
-- Dependencies: 249
-- Name: COLUMN parties.netting_eligible; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.parties.netting_eligible IS 'Whether transactions with this party can participate in netting cycles. Auto-true for IC.';


--
-- TOC entry 7036 (class 0 OID 0)
-- Dependencies: 249
-- Name: COLUMN parties.ic_settlement_method; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.parties.ic_settlement_method IS 'Default settlement method for IC transactions: NETTING, DIRECT_TRANSFER, IHB, MANUAL.';


--
-- TOC entry 7037 (class 0 OID 0)
-- Dependencies: 249
-- Name: COLUMN parties.ic_credit_limit; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.parties.ic_credit_limit IS 'Credit limit for intercompany exposure with this party.';


--
-- TOC entry 7038 (class 0 OID 0)
-- Dependencies: 249
-- Name: COLUMN parties.ic_current_exposure; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.parties.ic_current_exposure IS 'Current IC exposure (outstanding payables - receivables). Updated by triggers.';


--
-- TOC entry 7039 (class 0 OID 0)
-- Dependencies: 249
-- Name: COLUMN parties.ic_currency; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.parties.ic_currency IS 'Currency for intercompany position tracking.';


--
-- TOC entry 250 (class 1259 OID 32807)
-- Name: party_bank_accounts; Type: TABLE; Schema: public; Owner: vam_user
--

CREATE TABLE public.party_bank_accounts (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone,
    created_by character varying(255),
    updated_at timestamp(6) without time zone,
    updated_by character varying(255),
    version bigint,
    account_number character varying(34),
    bank_code character varying(11),
    bank_name character varying(255) NOT NULL,
    currency character varying(3) NOT NULL,
    holder_name character varying(255) NOT NULL,
    iban character varying(34),
    is_primary boolean,
    is_verified boolean,
    label character varying(255) NOT NULL,
    routing_number character varying(20),
    status character varying(20),
    verified_at timestamp(6) without time zone,
    verified_by character varying(255),
    party_id uuid NOT NULL,
    CONSTRAINT party_bank_accounts_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'SUSPENDED'::character varying, 'CLOSED'::character varying])::text[])))
);


ALTER TABLE public.party_bank_accounts OWNER TO vam_user;

--
-- TOC entry 251 (class 1259 OID 32821)
-- Name: party_documents; Type: TABLE; Schema: public; Owner: vam_user
--

CREATE TABLE public.party_documents (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone,
    created_by character varying(255),
    updated_at timestamp(6) without time zone,
    updated_by character varying(255),
    version bigint,
    category character varying(20) NOT NULL,
    document_number character varying(50),
    document_type character varying(30) NOT NULL,
    expiry_date date,
    file_hash character varying(255),
    file_name character varying(255),
    file_path character varying(255),
    file_size bigint,
    file_type character varying(20),
    issue_date date,
    issuing_authority character varying(255),
    issuing_country character varying(2),
    name character varying(255) NOT NULL,
    rejection_reason character varying(255),
    verification_status character varying(20) NOT NULL,
    verified_at timestamp(6) without time zone,
    verified_by character varying(255),
    party_id uuid NOT NULL,
    CONSTRAINT party_documents_category_check CHECK (((category)::text = ANY ((ARRAY['KYC'::character varying, 'LEGAL'::character varying, 'FINANCIAL'::character varying, 'IDENTITY'::character varying, 'OTHER'::character varying])::text[]))),
    CONSTRAINT party_documents_document_type_check CHECK (((document_type)::text = ANY ((ARRAY['TRADE_LICENSE'::character varying, 'TAX_CERTIFICATE'::character varying, 'VAT_CERTIFICATE'::character varying, 'INCORPORATION_CERT'::character varying, 'MEMORANDUM_ARTICLES'::character varying, 'BANK_STATEMENT'::character varying, 'POWER_OF_ATTORNEY'::character varying, 'BOARD_RESOLUTION'::character varying, 'SHAREHOLDER_REGISTER'::character varying, 'PASSPORT'::character varying, 'NATIONAL_ID'::character varying, 'VISA'::character varying, 'EMIRATES_ID'::character varying, 'UTILITY_BILL'::character varying, 'AUDIT_REPORT'::character varying, 'FINANCIAL_STATEMENT'::character varying, 'OTHER'::character varying])::text[]))),
    CONSTRAINT party_documents_verification_status_check CHECK (((verification_status)::text = ANY ((ARRAY['PENDING'::character varying, 'VERIFIED'::character varying, 'REJECTED'::character varying, 'EXPIRED'::character varying])::text[])))
);


ALTER TABLE public.party_documents OWNER TO vam_user;

--
-- TOC entry 302 (class 1259 OID 51527)
-- Name: payables; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.payables (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    version bigint DEFAULT 0,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by character varying(100),
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_by character varying(100),
    payable_number character varying(50) NOT NULL,
    external_reference character varying(100),
    invoice_number character varying(100),
    payable_type character varying(30) DEFAULT 'INVOICE'::character varying NOT NULL,
    corporate_id uuid NOT NULL,
    program_id uuid,
    virtual_account_id uuid,
    owning_entity_id uuid,
    owning_entity_code character varying(20),
    owning_entity_name character varying(200),
    party_id uuid,
    party_bank_account_id uuid,
    payment_route character varying(30) DEFAULT 'DIRECT'::character varying,
    payment_via_entity_id uuid,
    payment_via_entity_code character varying(20),
    payment_route_description character varying(500),
    pobo_request_id uuid,
    pobo_request_status character varying(30) DEFAULT 'NOT_REQUESTED'::character varying,
    pobo_transaction_ref character varying(50),
    pobo_ihb_loan_id uuid,
    pobo_recharge_id uuid,
    pobo_requested_at timestamp without time zone,
    pobo_requested_by character varying(100),
    pobo_actioned_at timestamp without time zone,
    pobo_actioned_by character varying(100),
    pobo_rejection_reason text,
    is_intercompany boolean DEFAULT false,
    counterparty_entity_id uuid,
    counterparty_entity_code character varying(20),
    counterparty_entity_name character varying(200),
    counterparty_receivable_id uuid,
    netting_eligible boolean DEFAULT false,
    netting_cycle_id uuid,
    netting_cycle_ref character varying(50),
    netting_entry_id uuid,
    netting_status character varying(30) DEFAULT 'NOT_INCLUDED'::character varying,
    netting_settlement_ref character varying(50),
    netting_settled_at timestamp without time zone,
    hierarchy_node_id uuid,
    hierarchy_path character varying(500),
    vendor_id uuid,
    vendor_name character varying(200),
    vendor_account character varying(50),
    vendor_bank character varying(100),
    vendor_bank_code character varying(20),
    vendor_reference character varying(100),
    payment_viban_id uuid,
    payment_viban character varying(34),
    currency_code character varying(3) DEFAULT 'AED'::character varying NOT NULL,
    gross_amount numeric(18,2) DEFAULT 0 NOT NULL,
    discount_amount numeric(18,2) DEFAULT 0,
    tax_amount numeric(18,2) DEFAULT 0,
    withholding_tax numeric(18,2) DEFAULT 0,
    net_amount numeric(18,2) DEFAULT 0 NOT NULL,
    paid_amount numeric(18,2) DEFAULT 0,
    outstanding_amount numeric(18,2) DEFAULT 0,
    invoice_date date,
    received_date date DEFAULT CURRENT_DATE,
    due_date date,
    payment_terms_days integer,
    status character varying(30) DEFAULT 'DRAFT'::character varying NOT NULL,
    payment_status character varying(30) DEFAULT 'UNPAID'::character varying,
    approval_required boolean DEFAULT true,
    approval_level integer DEFAULT 1,
    approved_by character varying(100),
    approved_at timestamp without time zone,
    rejection_reason character varying(500),
    scheduled_date date,
    payment_priority character varying(20) DEFAULT 'NORMAL'::character varying,
    payment_method character varying(30),
    payment_batch_id uuid,
    is_pobo boolean DEFAULT false,
    behalf_of_entity character varying(200),
    behalf_of_va_id uuid,
    payment_channel character varying(30),
    description text,
    notes text,
    metadata jsonb,
    has_invoice_document boolean DEFAULT false,
    document_count integer DEFAULT 0
);


ALTER TABLE public.payables OWNER TO postgres;

--
-- TOC entry 267 (class 1259 OID 33657)
-- Name: payment_batches; Type: TABLE; Schema: public; Owner: vam_user
--

CREATE TABLE public.payment_batches (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    batch_reference character varying(50) NOT NULL,
    batch_name character varying(200),
    corporate_id uuid NOT NULL,
    program_id uuid,
    source_va_id uuid,
    payment_count integer DEFAULT 0,
    total_amount numeric(18,2) DEFAULT 0,
    currency_code character varying(3) DEFAULT 'AED'::character varying NOT NULL,
    status character varying(30) DEFAULT 'DRAFT'::character varying NOT NULL,
    pending_count integer DEFAULT 0,
    success_count integer DEFAULT 0,
    failed_count integer DEFAULT 0,
    approval_required boolean DEFAULT true,
    approved_by character varying(100),
    approved_at timestamp without time zone,
    rejection_reason character varying(500),
    scheduled_date date,
    execution_start timestamp without time zone,
    execution_end timestamp without time zone,
    processing_notes text,
    error_summary text,
    created_by character varying(100),
    updated_by character varying(100),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    version bigint
);


ALTER TABLE public.payment_batches OWNER TO vam_user;

--
-- TOC entry 7041 (class 0 OID 0)
-- Dependencies: 267
-- Name: TABLE payment_batches; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON TABLE public.payment_batches IS 'Batch processing for bulk payments';


--
-- TOC entry 268 (class 1259 OID 33690)
-- Name: payment_executions; Type: TABLE; Schema: public; Owner: vam_user
--

CREATE TABLE public.payment_executions (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    payable_id uuid,
    batch_id uuid,
    transaction_id uuid,
    payment_reference character varying(100),
    execution_date timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    value_date date,
    source_va_id uuid,
    source_account character varying(50),
    beneficiary_name character varying(200),
    beneficiary_account character varying(50),
    beneficiary_bank character varying(100),
    beneficiary_bank_code character varying(20),
    amount numeric(18,2) NOT NULL,
    currency_code character varying(3) NOT NULL,
    status character varying(30) DEFAULT 'PENDING'::character varying NOT NULL,
    bank_reference character varying(100),
    bank_response_code character varying(20),
    bank_response_message character varying(500),
    is_pobo boolean DEFAULT false,
    behalf_of_entity character varying(200),
    error_code character varying(50),
    error_message character varying(500),
    retry_count integer DEFAULT 0,
    last_retry_at timestamp without time zone,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by character varying(255),
    updated_by character varying(255),
    version bigint
);


ALTER TABLE public.payment_executions OWNER TO vam_user;

--
-- TOC entry 7042 (class 0 OID 0)
-- Dependencies: 268
-- Name: TABLE payment_executions; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON TABLE public.payment_executions IS 'Individual payment execution tracking';


--
-- TOC entry 279 (class 1259 OID 34045)
-- Name: payment_charge_summary; Type: VIEW; Schema: public; Owner: postgres
--

CREATE VIEW public.payment_charge_summary AS
 SELECT pe.id AS execution_id,
    pe.payment_reference,
    pe.payable_id,
    pe.amount,
    cc.charge_code,
    cc.charge_type,
    cc.calculated_charge,
    cc.waived_amount,
    cc.final_charge
   FROM (public.payment_executions pe
     LEFT JOIN public.calculated_charges cc ON ((((cc.reference_type)::text = 'PAYMENT_EXECUTION'::text) AND (cc.reference_id = pe.id))));


ALTER VIEW public.payment_charge_summary OWNER TO postgres;

--
-- TOC entry 224 (class 1259 OID 30680)
-- Name: physical_accounts; Type: TABLE; Schema: public; Owner: vam_user
--

CREATE TABLE public.physical_accounts (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone,
    created_by character varying(255),
    updated_at timestamp(6) without time zone,
    updated_by character varying(255),
    version bigint DEFAULT 0,
    account_name character varying(255) NOT NULL,
    account_number character varying(255) NOT NULL,
    account_type character varying(255),
    available_balance numeric(18,2),
    bancs_account_id character varying(255),
    bancs_customer_id character varying(255),
    branch_code character varying(255),
    corporate_id uuid NOT NULL,
    currency_code character varying(3) NOT NULL,
    current_balance numeric(18,2),
    iban character varying(34),
    opened_date date,
    relationship_manager character varying(255),
    status character varying(255),
    bank_name character varying(255),
    bank_code character varying(20),
    bank_country character varying(2),
    branch_name character varying(255),
    entity_name character varying(255),
    entity_code character varying(20),
    bank_relationship character varying(20) DEFAULT 'INTERNAL'::character varying,
    data_source character varying(30) DEFAULT 'CORE_BANKING'::character varying,
    external_connection_id character varying(255),
    api_provider character varying(50),
    consent_expires_at timestamp without time zone,
    can_view_balance boolean DEFAULT true,
    can_view_transactions boolean DEFAULT true,
    can_initiate_payments boolean DEFAULT true,
    can_receive_transfers boolean DEFAULT true,
    can_host_virtual_accounts boolean DEFAULT true,
    pooling_eligible boolean DEFAULT true,
    sweep_eligible boolean DEFAULT true,
    ledger_balance numeric(18,2) DEFAULT 0,
    balance_as_of timestamp without time zone,
    interest_rate numeric(8,4),
    interest_type character varying(20),
    pooling_enabled boolean DEFAULT false,
    pool_id uuid,
    pool_reference character varying(50),
    sweep_enabled boolean DEFAULT false,
    sweep_role character varying(20),
    sweep_rule_id uuid,
    virtual_account_count integer DEFAULT 0,
    closed_date date,
    last_transaction_at timestamp without time zone,
    sync_status character varying(20) DEFAULT 'SYNCED'::character varying,
    last_sync_at timestamp without time zone,
    sync_error_message character varying(500),
    sync_frequency_minutes integer DEFAULT 0,
    next_sync_at timestamp without time zone,
    credit_facility_id uuid,
    overdraft_limit numeric(19,4),
    overdraft_utilized numeric(19,4) DEFAULT 0,
    overdraft_available numeric(19,4),
    interest_config_id uuid,
    effective_interest_rate numeric(8,5),
    shadow_va_id uuid,
    legal_entity_id uuid,
    CONSTRAINT physical_accounts_account_type_check CHECK (((account_type)::text = ANY ((ARRAY['CURRENT'::character varying, 'SAVINGS'::character varying, 'ESCROW'::character varying, 'POOL'::character varying, 'COLLECTION'::character varying])::text[]))),
    CONSTRAINT physical_accounts_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'INACTIVE'::character varying, 'FROZEN'::character varying, 'CLOSED'::character varying, 'DORMANT'::character varying])::text[])))
);


ALTER TABLE public.physical_accounts OWNER TO vam_user;

--
-- TOC entry 7044 (class 0 OID 0)
-- Dependencies: 224
-- Name: COLUMN physical_accounts.credit_facility_id; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.physical_accounts.credit_facility_id IS 'Linked overdraft facility from CBS';


--
-- TOC entry 7045 (class 0 OID 0)
-- Dependencies: 224
-- Name: COLUMN physical_accounts.overdraft_limit; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.physical_accounts.overdraft_limit IS 'Sanctioned overdraft limit';


--
-- TOC entry 7046 (class 0 OID 0)
-- Dependencies: 224
-- Name: COLUMN physical_accounts.legal_entity_id; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.physical_accounts.legal_entity_id IS 'Link to LegalEntity for ownership tracking and filtering';


--
-- TOC entry 278 (class 1259 OID 33998)
-- Name: pobo_authorizations; Type: TABLE; Schema: public; Owner: vam_user
--

CREATE TABLE public.pobo_authorizations (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    authorization_code character varying(50) NOT NULL,
    payer_entity_id uuid NOT NULL,
    payer_entity_code character varying(50) NOT NULL,
    payer_va_id uuid,
    behalf_entity_id uuid NOT NULL,
    behalf_entity_code character varying(50) NOT NULL,
    behalf_va_id uuid,
    authorization_type character varying(30) DEFAULT 'FULL'::character varying,
    single_payment_limit numeric(18,2),
    daily_limit numeric(18,2),
    monthly_limit numeric(18,2),
    currency_code character varying(3) DEFAULT 'AED'::character varying,
    used_today numeric(18,2) DEFAULT 0,
    used_this_month numeric(18,2) DEFAULT 0,
    last_reset_date date DEFAULT CURRENT_DATE,
    allowed_payment_types character varying(200),
    allowed_vendor_ids text,
    auto_recharge boolean DEFAULT true,
    recharge_service_fee_rate numeric(8,4) DEFAULT 0,
    requires_approval boolean DEFAULT true,
    effective_from date DEFAULT CURRENT_DATE NOT NULL,
    effective_to date,
    status character varying(20) DEFAULT 'ACTIVE'::character varying,
    created_by character varying(100),
    approved_by character varying(100),
    approved_at timestamp without time zone,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_by character varying(255),
    version bigint
);


ALTER TABLE public.pobo_authorizations OWNER TO vam_user;

--
-- TOC entry 7047 (class 0 OID 0)
-- Dependencies: 278
-- Name: TABLE pobo_authorizations; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON TABLE public.pobo_authorizations IS 'POBO authorization between entities';


--
-- TOC entry 233 (class 1259 OID 30876)
-- Name: pool_interest_calculations; Type: TABLE; Schema: public; Owner: vam_user
--

CREATE TABLE public.pool_interest_calculations (
    id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    pool_id uuid NOT NULL,
    calculation_date date NOT NULL,
    pool_balance numeric(18,2) NOT NULL,
    interest_rate numeric(8,4) NOT NULL,
    gross_interest numeric(18,2) NOT NULL,
    net_interest numeric(18,2) NOT NULL,
    status character varying(20) DEFAULT 'PENDING'::character varying,
    approved_by character varying(100),
    approved_at timestamp without time zone,
    posted_at timestamp without time zone,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by character varying(100),
    updated_by character varying(100),
    version bigint DEFAULT 0
);


ALTER TABLE public.pool_interest_calculations OWNER TO vam_user;

--
-- TOC entry 232 (class 1259 OID 30851)
-- Name: pool_members; Type: TABLE; Schema: public; Owner: vam_user
--

CREATE TABLE public.pool_members (
    id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    pool_id uuid NOT NULL,
    account_id uuid NOT NULL,
    account_number character varying(34) NOT NULL,
    entity_code character varying(20),
    entity_name character varying(100),
    current_balance numeric(18,2) DEFAULT 0,
    contribution_percent numeric(8,4) DEFAULT 0,
    interest_allocation numeric(18,2) DEFAULT 0,
    status character varying(20) DEFAULT 'ACTIVE'::character varying,
    joined_date date DEFAULT CURRENT_DATE,
    left_date date,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by character varying(100),
    updated_by character varying(100),
    version bigint DEFAULT 0
);


ALTER TABLE public.pool_members OWNER TO vam_user;

--
-- TOC entry 284 (class 1259 OID 42283)
-- Name: program_charge_overrides; Type: TABLE; Schema: public; Owner: vam_user
--

CREATE TABLE public.program_charge_overrides (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    program_id uuid NOT NULL,
    charge_code character varying(30) NOT NULL,
    charge_config_id uuid,
    override_percentage numeric(8,4),
    override_fixed numeric(18,2),
    override_minimum numeric(18,2),
    override_maximum numeric(18,2),
    is_waived boolean DEFAULT false,
    waiver_reason character varying(200),
    waiver_approved_by character varying(100),
    effective_from date DEFAULT CURRENT_DATE NOT NULL,
    effective_to date,
    status character varying(20) DEFAULT 'ACTIVE'::character varying,
    notes text,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by character varying(100),
    updated_by character varying(100),
    version bigint DEFAULT 0,
    CONSTRAINT program_charge_overrides_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'INACTIVE'::character varying, 'PENDING_APPROVAL'::character varying, 'EXPIRED'::character varying])::text[])))
);


ALTER TABLE public.program_charge_overrides OWNER TO vam_user;

--
-- TOC entry 7048 (class 0 OID 0)
-- Dependencies: 284
-- Name: TABLE program_charge_overrides; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON TABLE public.program_charge_overrides IS 'Program-specific charge overrides and waivers';


--
-- TOC entry 7049 (class 0 OID 0)
-- Dependencies: 284
-- Name: COLUMN program_charge_overrides.override_percentage; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.program_charge_overrides.override_percentage IS 'Override percentage rate (NULL = use base config)';


--
-- TOC entry 7050 (class 0 OID 0)
-- Dependencies: 284
-- Name: COLUMN program_charge_overrides.override_fixed; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.program_charge_overrides.override_fixed IS 'Override fixed amount (NULL = use base config)';


--
-- TOC entry 7051 (class 0 OID 0)
-- Dependencies: 284
-- Name: COLUMN program_charge_overrides.is_waived; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.program_charge_overrides.is_waived IS 'If true, this charge is completely waived for this program';


--
-- TOC entry 261 (class 1259 OID 33396)
-- Name: receivables; Type: TABLE; Schema: public; Owner: vam_user
--

CREATE TABLE public.receivables (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    receivable_number character varying(50) NOT NULL,
    external_reference character varying(100),
    receivable_type character varying(30) DEFAULT 'INVOICE'::character varying NOT NULL,
    corporate_id uuid NOT NULL,
    program_id uuid,
    virtual_account_id uuid,
    hierarchy_node_id uuid,
    hierarchy_path character varying(500),
    customer_id uuid,
    customer_name character varying(200),
    customer_email character varying(200),
    customer_mobile character varying(30),
    customer_reference character varying(100),
    primary_viban_id uuid,
    viban character varying(34),
    currency_code character varying(3) DEFAULT 'AED'::character varying NOT NULL,
    gross_amount numeric(18,2) NOT NULL,
    discount_amount numeric(18,2) DEFAULT 0,
    tax_amount numeric(18,2) DEFAULT 0,
    net_amount numeric(18,2) NOT NULL,
    paid_amount numeric(18,2) DEFAULT 0,
    outstanding_amount numeric(18,2),
    issue_date date DEFAULT CURRENT_DATE NOT NULL,
    due_date date,
    payment_terms_days integer,
    status character varying(30) DEFAULT 'OPEN'::character varying NOT NULL,
    payment_status character varying(30) DEFAULT 'PENDING'::character varying,
    auto_reconcile boolean DEFAULT true,
    amount_tolerance_percent numeric(5,2) DEFAULT 0,
    min_acceptable_amount numeric(18,2),
    max_acceptable_amount numeric(18,2),
    allow_partial_payment boolean DEFAULT true,
    allow_overpayment boolean DEFAULT false,
    collection_channel character varying(30),
    platform character varying(50),
    platform_order_id character varying(100),
    platform_fee numeric(18,2),
    escrow_status character varying(30),
    merchant_id character varying(30),
    terminal_id character varying(20),
    description text,
    notes text,
    metadata jsonb,
    created_by character varying(100),
    updated_by character varying(100),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    version bigint,
    owning_entity_id uuid,
    owning_entity_code character varying(20),
    owning_entity_name character varying(200),
    customer_party_id uuid,
    customer_party_bank_account_id uuid,
    is_intercompany boolean DEFAULT false,
    intercompany_entity_id uuid,
    intercompany_entity_code character varying(20),
    intercompany_entity_name character varying(200),
    counterparty_payable_id uuid,
    is_cobo boolean DEFAULT false,
    cobo_collector_entity_id uuid,
    cobo_collector_entity_code character varying(20),
    cobo_collector_entity_name character varying(200),
    cobo_request_id uuid,
    cobo_request_status character varying(30),
    cobo_transaction_ref character varying(50),
    cobo_recharge_id uuid,
    cobo_ihb_deposit_id uuid,
    cobo_requested_at timestamp without time zone,
    cobo_requested_by character varying(100),
    cobo_actioned_at timestamp without time zone,
    cobo_actioned_by character varying(100),
    netting_eligible boolean DEFAULT false,
    netting_cycle_id uuid,
    netting_cycle_ref character varying(50),
    netting_entry_id uuid,
    netting_status character varying(30) DEFAULT 'NOT_INCLUDED'::character varying,
    netting_settlement_ref character varying(50),
    netting_settled_at timestamp without time zone,
    collection_route character varying(30) DEFAULT 'DIRECT'::character varying,
    CONSTRAINT chk_receivable_amounts CHECK (((net_amount >= (0)::numeric) AND (paid_amount >= (0)::numeric))),
    CONSTRAINT chk_receivable_cobo_status CHECK (((cobo_request_status IS NULL) OR ((cobo_request_status)::text = ANY ((ARRAY['NOT_REQUESTED'::character varying, 'PENDING_ENTITY_APPROVAL'::character varying, 'PENDING_TREASURY_APPROVAL'::character varying, 'APPROVED'::character varying, 'REJECTED'::character varying, 'COLLECTED'::character varying, 'FAILED'::character varying])::text[])))),
    CONSTRAINT chk_receivable_collection_route CHECK (((collection_route)::text = ANY ((ARRAY['DIRECT'::character varying, 'COBO'::character varying, 'INTERCOMPANY'::character varying, 'NETTING'::character varying])::text[]))),
    CONSTRAINT chk_receivable_netting_status CHECK (((netting_status IS NULL) OR ((netting_status)::text = ANY ((ARRAY['NOT_INCLUDED'::character varying, 'PENDING'::character varying, 'INCLUDED'::character varying, 'SETTLED'::character varying, 'EXCLUDED'::character varying])::text[])))),
    CONSTRAINT chk_receivable_status CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'OPEN'::character varying, 'PARTIAL'::character varying, 'PAID'::character varying, 'OVERDUE'::character varying, 'DISPUTED'::character varying, 'CANCELLED'::character varying, 'WRITTEN_OFF'::character varying, 'PENDING_COBO'::character varying, 'COBO_APPROVED'::character varying, 'COBO_REJECTED'::character varying, 'PENDING_NETTING'::character varying, 'NETTED'::character varying])::text[])))
);


ALTER TABLE public.receivables OWNER TO vam_user;

--
-- TOC entry 7052 (class 0 OID 0)
-- Dependencies: 261
-- Name: TABLE receivables; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON TABLE public.receivables IS 'Core receivables table with VIBAN integration for auto-reconciliation';


--
-- TOC entry 7053 (class 0 OID 0)
-- Dependencies: 261
-- Name: COLUMN receivables.owning_entity_id; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.receivables.owning_entity_id IS 'Legal entity that owns this receivable (e.g., SUB-DUBAI invoiced the customer)';


--
-- TOC entry 7054 (class 0 OID 0)
-- Dependencies: 261
-- Name: COLUMN receivables.owning_entity_code; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.receivables.owning_entity_code IS 'Code of the owning legal entity for display';


--
-- TOC entry 7055 (class 0 OID 0)
-- Dependencies: 261
-- Name: COLUMN receivables.owning_entity_name; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.receivables.owning_entity_name IS 'Name of the owning legal entity for display';


--
-- TOC entry 7056 (class 0 OID 0)
-- Dependencies: 261
-- Name: COLUMN receivables.customer_party_id; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.receivables.customer_party_id IS 'Link to Party master for this customer (provides COBO eligibility, IC flag, etc.)';


--
-- TOC entry 7057 (class 0 OID 0)
-- Dependencies: 261
-- Name: COLUMN receivables.customer_party_bank_account_id; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.receivables.customer_party_bank_account_id IS 'Customer bank account for refunds/returns';


--
-- TOC entry 7058 (class 0 OID 0)
-- Dependencies: 261
-- Name: COLUMN receivables.is_intercompany; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.receivables.is_intercompany IS 'True when customer is another group entity (intercompany sale)';


--
-- TOC entry 7059 (class 0 OID 0)
-- Dependencies: 261
-- Name: COLUMN receivables.intercompany_entity_id; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.receivables.intercompany_entity_id IS 'If intercompany, the group entity that is the customer';


--
-- TOC entry 7060 (class 0 OID 0)
-- Dependencies: 261
-- Name: COLUMN receivables.intercompany_entity_code; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.receivables.intercompany_entity_code IS 'Code of the intercompany entity for display';


--
-- TOC entry 7061 (class 0 OID 0)
-- Dependencies: 261
-- Name: COLUMN receivables.intercompany_entity_name; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.receivables.intercompany_entity_name IS 'Name of the intercompany entity for display';


--
-- TOC entry 7062 (class 0 OID 0)
-- Dependencies: 261
-- Name: COLUMN receivables.counterparty_payable_id; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.receivables.counterparty_payable_id IS 'Cross-reference to matching payable on counterparty side (IC receivable = counterparty payable)';


--
-- TOC entry 7063 (class 0 OID 0)
-- Dependencies: 261
-- Name: COLUMN receivables.is_cobo; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.receivables.is_cobo IS 'True if this receivable is collected via COBO (centralized collection)';


--
-- TOC entry 7064 (class 0 OID 0)
-- Dependencies: 261
-- Name: COLUMN receivables.cobo_collector_entity_id; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.receivables.cobo_collector_entity_id IS 'Entity collecting on behalf (usually HQ treasury)';


--
-- TOC entry 7065 (class 0 OID 0)
-- Dependencies: 261
-- Name: COLUMN receivables.cobo_collector_entity_code; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.receivables.cobo_collector_entity_code IS 'Code of the collecting entity for display';


--
-- TOC entry 7066 (class 0 OID 0)
-- Dependencies: 261
-- Name: COLUMN receivables.cobo_collector_entity_name; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.receivables.cobo_collector_entity_name IS 'Name of the collecting entity for display';


--
-- TOC entry 7067 (class 0 OID 0)
-- Dependencies: 261
-- Name: COLUMN receivables.cobo_request_id; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.receivables.cobo_request_id IS 'Reference to COBO request/authorization record';


--
-- TOC entry 7068 (class 0 OID 0)
-- Dependencies: 261
-- Name: COLUMN receivables.cobo_request_status; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.receivables.cobo_request_status IS 'COBO workflow status: NOT_REQUESTED, PENDING_APPROVAL, APPROVED, REJECTED, COLLECTED, FAILED';


--
-- TOC entry 7069 (class 0 OID 0)
-- Dependencies: 261
-- Name: COLUMN receivables.cobo_transaction_ref; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.receivables.cobo_transaction_ref IS 'Transaction reference for executed COBO collection';


--
-- TOC entry 7070 (class 0 OID 0)
-- Dependencies: 261
-- Name: COLUMN receivables.cobo_recharge_id; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.receivables.cobo_recharge_id IS 'Intercompany recharge record created for COBO settlement';


--
-- TOC entry 7071 (class 0 OID 0)
-- Dependencies: 261
-- Name: COLUMN receivables.cobo_ihb_deposit_id; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.receivables.cobo_ihb_deposit_id IS 'IHB deposit created when treasury forwards collection to subsidiary';


--
-- TOC entry 7072 (class 0 OID 0)
-- Dependencies: 261
-- Name: COLUMN receivables.cobo_requested_at; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.receivables.cobo_requested_at IS 'When COBO was requested';


--
-- TOC entry 7073 (class 0 OID 0)
-- Dependencies: 261
-- Name: COLUMN receivables.cobo_requested_by; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.receivables.cobo_requested_by IS 'Who requested COBO';


--
-- TOC entry 7074 (class 0 OID 0)
-- Dependencies: 261
-- Name: COLUMN receivables.cobo_actioned_at; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.receivables.cobo_actioned_at IS 'When COBO request was approved/rejected';


--
-- TOC entry 7075 (class 0 OID 0)
-- Dependencies: 261
-- Name: COLUMN receivables.cobo_actioned_by; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.receivables.cobo_actioned_by IS 'Who actioned the COBO request';


--
-- TOC entry 7076 (class 0 OID 0)
-- Dependencies: 261
-- Name: COLUMN receivables.netting_eligible; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.receivables.netting_eligible IS 'True if this receivable can participate in netting cycles (auto-true for IC)';


--
-- TOC entry 7077 (class 0 OID 0)
-- Dependencies: 261
-- Name: COLUMN receivables.netting_cycle_id; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.receivables.netting_cycle_id IS 'Current netting cycle this receivable is included in';


--
-- TOC entry 7078 (class 0 OID 0)
-- Dependencies: 261
-- Name: COLUMN receivables.netting_cycle_ref; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.receivables.netting_cycle_ref IS 'Reference number of the netting cycle for display';


--
-- TOC entry 7079 (class 0 OID 0)
-- Dependencies: 261
-- Name: COLUMN receivables.netting_entry_id; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.receivables.netting_entry_id IS 'Entry ID within the netting cycle';


--
-- TOC entry 7080 (class 0 OID 0)
-- Dependencies: 261
-- Name: COLUMN receivables.netting_status; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.receivables.netting_status IS 'Netting status: NOT_INCLUDED, PENDING, INCLUDED, SETTLED, EXCLUDED';


--
-- TOC entry 7081 (class 0 OID 0)
-- Dependencies: 261
-- Name: COLUMN receivables.netting_settlement_ref; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.receivables.netting_settlement_ref IS 'Settlement reference from netting cycle execution';


--
-- TOC entry 7082 (class 0 OID 0)
-- Dependencies: 261
-- Name: COLUMN receivables.netting_settled_at; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.receivables.netting_settled_at IS 'When this receivable was settled via netting';


--
-- TOC entry 7083 (class 0 OID 0)
-- Dependencies: 261
-- Name: COLUMN receivables.collection_route; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.receivables.collection_route IS 'How this receivable will be collected: DIRECT, COBO, INTERCOMPANY, NETTING';


--
-- TOC entry 265 (class 1259 OID 33576)
-- Name: receivable_aging; Type: VIEW; Schema: public; Owner: postgres
--

CREATE VIEW public.receivable_aging AS
 SELECT id,
    receivable_number,
    corporate_id,
    program_id,
    customer_name,
    net_amount,
    paid_amount,
    outstanding_amount,
    currency_code,
    due_date,
    status,
    hierarchy_node_id,
    hierarchy_path,
        CASE
            WHEN ((status)::text = 'PAID'::text) THEN 'PAID'::text
            WHEN (due_date IS NULL) THEN 'NO_DUE_DATE'::text
            WHEN (CURRENT_DATE <= due_date) THEN 'CURRENT'::text
            WHEN (CURRENT_DATE <= (due_date + 30)) THEN '1_30_DAYS'::text
            WHEN (CURRENT_DATE <= (due_date + 60)) THEN '31_60_DAYS'::text
            WHEN (CURRENT_DATE <= (due_date + 90)) THEN '61_90_DAYS'::text
            ELSE 'OVER_90_DAYS'::text
        END AS aging_bucket,
        CASE
            WHEN (due_date IS NULL) THEN 0
            ELSE GREATEST(0, (CURRENT_DATE - due_date))
        END AS days_overdue
   FROM public.receivables r
  WHERE ((status)::text <> ALL ((ARRAY['CANCELLED'::character varying, 'WRITTEN_OFF'::character varying])::text[]));


ALTER VIEW public.receivable_aging OWNER TO postgres;

--
-- TOC entry 7084 (class 0 OID 0)
-- Dependencies: 265
-- Name: VIEW receivable_aging; Type: COMMENT; Schema: public; Owner: postgres
--

COMMENT ON VIEW public.receivable_aging IS 'Receivables aging analysis by due date';


--
-- TOC entry 266 (class 1259 OID 33581)
-- Name: receivable_hierarchy_summary; Type: VIEW; Schema: public; Owner: postgres
--

CREATE VIEW public.receivable_hierarchy_summary AS
 SELECT hierarchy_node_id,
    hierarchy_path,
    corporate_id,
    program_id,
    currency_code,
    count(*) AS receivable_count,
    count(
        CASE
            WHEN ((status)::text = 'OPEN'::text) THEN 1
            ELSE NULL::integer
        END) AS open_count,
    count(
        CASE
            WHEN ((status)::text = 'PARTIAL'::text) THEN 1
            ELSE NULL::integer
        END) AS partial_count,
    count(
        CASE
            WHEN ((status)::text = 'PAID'::text) THEN 1
            ELSE NULL::integer
        END) AS paid_count,
    count(
        CASE
            WHEN ((status)::text = 'OVERDUE'::text) THEN 1
            ELSE NULL::integer
        END) AS overdue_count,
    sum(net_amount) AS total_amount,
    sum(paid_amount) AS total_paid,
    sum(outstanding_amount) AS total_outstanding
   FROM public.receivables r
  WHERE ((status)::text <> ALL ((ARRAY['CANCELLED'::character varying, 'WRITTEN_OFF'::character varying, 'DRAFT'::character varying])::text[]))
  GROUP BY hierarchy_node_id, hierarchy_path, corporate_id, program_id, currency_code;


ALTER VIEW public.receivable_hierarchy_summary OWNER TO postgres;

--
-- TOC entry 7086 (class 0 OID 0)
-- Dependencies: 266
-- Name: VIEW receivable_hierarchy_summary; Type: COMMENT; Schema: public; Owner: postgres
--

COMMENT ON VIEW public.receivable_hierarchy_summary IS 'Receivables summary aggregated by hierarchy node';


--
-- TOC entry 263 (class 1259 OID 33513)
-- Name: receivable_line_items; Type: TABLE; Schema: public; Owner: vam_user
--

CREATE TABLE public.receivable_line_items (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    receivable_id uuid NOT NULL,
    line_number integer NOT NULL,
    item_code character varying(50),
    description character varying(500),
    quantity numeric(10,3) DEFAULT 1,
    unit_price numeric(18,2),
    discount_percent numeric(5,2) DEFAULT 0,
    tax_percent numeric(5,2) DEFAULT 0,
    line_amount numeric(18,2) NOT NULL,
    metadata jsonb,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


ALTER TABLE public.receivable_line_items OWNER TO vam_user;

--
-- TOC entry 262 (class 1259 OID 33469)
-- Name: receivable_payments; Type: TABLE; Schema: public; Owner: vam_user
--

CREATE TABLE public.receivable_payments (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    receivable_id uuid NOT NULL,
    transaction_id uuid,
    viban_id uuid,
    payment_reference character varying(100),
    payment_date timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    value_date date,
    payment_amount numeric(18,2) NOT NULL,
    applied_amount numeric(18,2) NOT NULL,
    unapplied_amount numeric(18,2) DEFAULT 0,
    currency_code character varying(3) NOT NULL,
    payer_name character varying(200),
    payer_account character varying(50),
    payer_bank character varying(100),
    remittance_info text,
    match_type character varying(20) DEFAULT 'MANUAL'::character varying NOT NULL,
    match_confidence integer,
    match_reason character varying(500),
    matched_by character varying(100),
    matched_at timestamp without time zone,
    status character varying(20) DEFAULT 'APPLIED'::character varying NOT NULL,
    reversed_at timestamp without time zone,
    reversal_reason character varying(500),
    reversal_reference character varying(100),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by character varying(255),
    updated_by character varying(255),
    version bigint
);


ALTER TABLE public.receivable_payments OWNER TO vam_user;

--
-- TOC entry 7088 (class 0 OID 0)
-- Dependencies: 262
-- Name: TABLE receivable_payments; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON TABLE public.receivable_payments IS 'Payment matching records linking transactions to receivables';


--
-- TOC entry 271 (class 1259 OID 33770)
-- Name: scheduled_job_executions; Type: TABLE; Schema: public; Owner: vam_user
--

CREATE TABLE public.scheduled_job_executions (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    job_name character varying(100) NOT NULL,
    job_type character varying(50) NOT NULL,
    started_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    completed_at timestamp without time zone,
    duration_ms bigint,
    status character varying(20) DEFAULT 'RUNNING'::character varying NOT NULL,
    records_processed integer DEFAULT 0,
    records_success integer DEFAULT 0,
    records_failed integer DEFAULT 0,
    error_message text,
    error_stack text,
    parameters jsonb,
    result_summary jsonb,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


ALTER TABLE public.scheduled_job_executions OWNER TO vam_user;

--
-- TOC entry 7089 (class 0 OID 0)
-- Dependencies: 271
-- Name: TABLE scheduled_job_executions; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON TABLE public.scheduled_job_executions IS 'Scheduled job execution history';


--
-- TOC entry 230 (class 1259 OID 30797)
-- Name: sweep_executions; Type: TABLE; Schema: public; Owner: vam_user
--

CREATE TABLE public.sweep_executions (
    id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    execution_reference character varying(30) NOT NULL,
    rule_id uuid NOT NULL,
    rule_name character varying(100),
    source_account_id uuid NOT NULL,
    source_account_number character varying(34),
    source_entity_code character varying(20),
    target_account_id uuid NOT NULL,
    target_account_number character varying(34),
    target_entity_code character varying(20),
    sweep_amount numeric(18,2) NOT NULL,
    currency_code character varying(3) DEFAULT 'AED'::character varying,
    balance_before numeric(18,2),
    balance_after numeric(18,2),
    status character varying(20) NOT NULL,
    error_message text,
    execution_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    completed_at timestamp without time zone,
    bancs_transaction_ref character varying(50),
    bancs_sync_status character varying(20) DEFAULT 'PENDING'::character varying,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by character varying(100),
    updated_by character varying(100),
    version bigint DEFAULT 0,
    ihb_enabled boolean DEFAULT false,
    ihb_deposit_id uuid,
    ihb_loan_id uuid
);


ALTER TABLE public.sweep_executions OWNER TO vam_user;

--
-- TOC entry 7090 (class 0 OID 0)
-- Dependencies: 230
-- Name: COLUMN sweep_executions.ihb_enabled; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.sweep_executions.ihb_enabled IS 'True when sweep created IHB intercompany positions';


--
-- TOC entry 7091 (class 0 OID 0)
-- Dependencies: 230
-- Name: COLUMN sweep_executions.ihb_deposit_id; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.sweep_executions.ihb_deposit_id IS 'IHB Deposit created for source entity when sweeping to Treasury';


--
-- TOC entry 7092 (class 0 OID 0)
-- Dependencies: 230
-- Name: COLUMN sweep_executions.ihb_loan_id; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.sweep_executions.ihb_loan_id IS 'IHB Loan created for reverse sweeps or deficit funding';


--
-- TOC entry 229 (class 1259 OID 30778)
-- Name: sweep_rule_sources; Type: TABLE; Schema: public; Owner: vam_user
--

CREATE TABLE public.sweep_rule_sources (
    id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    rule_id uuid NOT NULL,
    account_id uuid NOT NULL,
    account_number character varying(34) NOT NULL,
    entity_code character varying(20),
    entity_name character varying(100),
    currency_code character varying(3) DEFAULT 'AED'::character varying,
    bank_name character varying(100),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by character varying(100),
    updated_by character varying(100),
    version bigint DEFAULT 0
);


ALTER TABLE public.sweep_rule_sources OWNER TO vam_user;

--
-- TOC entry 228 (class 1259 OID 30752)
-- Name: sweep_rules; Type: TABLE; Schema: public; Owner: vam_user
--

CREATE TABLE public.sweep_rules (
    id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    rule_reference character varying(20) NOT NULL,
    rule_name character varying(100) NOT NULL,
    sweep_type character varying(20) NOT NULL,
    target_amount numeric(18,2),
    threshold_min numeric(18,2),
    threshold_max numeric(18,2),
    percentage numeric(5,2),
    target_account_id uuid NOT NULL,
    target_account_number character varying(34),
    target_entity_code character varying(20),
    frequency character varying(20) DEFAULT 'DAILY'::character varying NOT NULL,
    execution_time time without time zone DEFAULT '18:00:00'::time without time zone,
    execution_day integer,
    priority integer DEFAULT 1,
    status character varying(20) DEFAULT 'ACTIVE'::character varying,
    total_swept numeric(18,2) DEFAULT 0,
    execution_count integer DEFAULT 0,
    last_execution timestamp without time zone,
    next_execution timestamp without time zone,
    currency_code character varying(3) DEFAULT 'AED'::character varying,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by character varying(100),
    updated_by character varying(100),
    version bigint DEFAULT 0
);


ALTER TABLE public.sweep_rules OWNER TO vam_user;

--
-- TOC entry 273 (class 1259 OID 33826)
-- Name: tax_configurations; Type: TABLE; Schema: public; Owner: vam_user
--

CREATE TABLE public.tax_configurations (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tax_code character varying(30) NOT NULL,
    tax_name character varying(100) NOT NULL,
    description text,
    tax_type character varying(30) NOT NULL,
    jurisdiction_id uuid,
    jurisdiction_code character varying(20),
    tax_category character varying(50),
    rate_percentage numeric(8,4) NOT NULL,
    minimum_amount numeric(18,2) DEFAULT 0,
    maximum_amount numeric(18,2),
    applies_to_payables boolean DEFAULT true,
    applies_to_receivables boolean DEFAULT true,
    applies_to_services boolean DEFAULT true,
    applies_to_goods boolean DEFAULT true,
    is_withholding boolean DEFAULT false,
    withholding_entity_type character varying(50),
    is_recoverable boolean DEFAULT true,
    recovery_percentage numeric(8,4) DEFAULT 100,
    tax_payable_account character varying(50),
    tax_receivable_account character varying(50),
    effective_from date DEFAULT CURRENT_DATE NOT NULL,
    effective_to date,
    status character varying(20) DEFAULT 'ACTIVE'::character varying,
    created_by character varying(100),
    updated_by character varying(100),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    version bigint DEFAULT 0
);


ALTER TABLE public.tax_configurations OWNER TO vam_user;

--
-- TOC entry 7093 (class 0 OID 0)
-- Dependencies: 273
-- Name: TABLE tax_configurations; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON TABLE public.tax_configurations IS 'Tax rates and rules by jurisdiction and type';


--
-- TOC entry 272 (class 1259 OID 33802)
-- Name: tax_jurisdictions; Type: TABLE; Schema: public; Owner: vam_user
--

CREATE TABLE public.tax_jurisdictions (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    jurisdiction_code character varying(20) NOT NULL,
    jurisdiction_name character varying(100) NOT NULL,
    country_code character varying(3) NOT NULL,
    region_code character varying(20),
    supports_vat boolean DEFAULT true,
    supports_gst boolean DEFAULT false,
    supports_withholding boolean DEFAULT true,
    supports_sales_tax boolean DEFAULT false,
    tax_authority_name character varying(200),
    tax_authority_id character varying(50),
    reporting_currency character varying(3) DEFAULT 'AED'::character varying,
    vat_registration_threshold numeric(18,2),
    withholding_threshold numeric(18,2),
    status character varying(20) DEFAULT 'ACTIVE'::character varying,
    effective_from date DEFAULT CURRENT_DATE NOT NULL,
    effective_to date,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    version bigint DEFAULT 0,
    created_by character varying(100),
    updated_by character varying(100)
);


ALTER TABLE public.tax_jurisdictions OWNER TO vam_user;

--
-- TOC entry 7094 (class 0 OID 0)
-- Dependencies: 272
-- Name: TABLE tax_jurisdictions; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON TABLE public.tax_jurisdictions IS 'Tax jurisdictions with regulatory requirements';


--
-- TOC entry 225 (class 1259 OID 30694)
-- Name: unified_programs; Type: TABLE; Schema: public; Owner: vam_user
--

CREATE TABLE public.unified_programs (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone,
    created_by character varying(255),
    updated_at timestamp(6) without time zone,
    updated_by character varying(255),
    version bigint DEFAULT 0 NOT NULL,
    auto_reconciliation boolean,
    corporate_id uuid NOT NULL,
    currency_code character varying(3) NOT NULL,
    description text,
    effective_from date,
    effective_to date,
    escrow_enabled boolean,
    ihb_enabled boolean,
    max_virtual_accounts integer,
    min_balance_threshold numeric(18,2),
    physical_account_id uuid NOT NULL,
    program_code character varying(255) NOT NULL,
    program_name character varying(255) NOT NULL,
    program_type character varying(255) NOT NULL,
    settlement_frequency character varying(255),
    settlement_time time(6) without time zone,
    status character varying(255),
    va_format character varying(255),
    va_prefix character varying(10),
    viban_enabled boolean,
    wallet_enabled boolean,
    allow_bulk_operations boolean,
    allow_payment boolean,
    allow_topup boolean,
    allow_transfer boolean,
    allow_withdrawal boolean,
    auto_kyc boolean,
    brand_logo_url character varying(255),
    brand_name character varying(255),
    current_va_count integer,
    default_daily_limit numeric(18,2),
    default_daily_topup_limit numeric(18,2),
    default_max_balance numeric(18,2),
    default_monthly_limit numeric(18,2),
    default_monthly_topup_limit numeric(18,2),
    default_per_txn_limit numeric(18,2),
    default_wallet_type character varying(20),
    default_weekly_limit numeric(18,2),
    default_yearly_limit numeric(18,2),
    inactive_expiry_days integer,
    issuance_fee numeric(18,2),
    kyc_required boolean,
    kyc_validity_days integer,
    max_topup numeric(18,2),
    max_withdrawal numeric(18,2),
    min_kyc_level integer,
    min_topup numeric(18,2),
    min_withdrawal numeric(18,2),
    monthly_fee numeric(18,2),
    topup_fee_flat numeric(18,2),
    topup_fee_percent numeric(8,4),
    transfer_fee_flat numeric(18,2),
    transfer_fee_percent numeric(8,4),
    wallet_expiry_days integer,
    withdrawal_fee_flat numeric(18,2),
    withdrawal_fee_percent numeric(8,4),
    hierarchy_enabled boolean DEFAULT false,
    hierarchy_depth integer DEFAULT 7,
    default_hierarchy_template character varying(50),
    root_hierarchy_node_id uuid,
    default_viban_pool_id uuid,
    viban_generation_strategy character varying(30) DEFAULT 'SEQUENTIAL'::character varying,
    balance_aggregation_interval_minutes integer,
    corporate_card_enabled boolean,
    gift_card_enabled boolean,
    loyalty_enabled boolean,
    mobile_money_enabled boolean,
    realtime_balance_propagation boolean,
    viban_bank_code character varying(10),
    viban_prefix character varying(20),
    legal_entity_id uuid,
    CONSTRAINT unified_programs_program_type_check CHECK (((program_type)::text = ANY ((ARRAY['COLLECTION'::character varying, 'VIBAN'::character varying, 'ESCROW'::character varying, 'WALLET'::character varying, 'IHB'::character varying, 'PAYABLES'::character varying])::text[]))),
    CONSTRAINT unified_programs_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'INACTIVE'::character varying, 'SUSPENDED'::character varying, 'PENDING_APPROVAL'::character varying])::text[])))
);


ALTER TABLE public.unified_programs OWNER TO vam_user;

--
-- TOC entry 7095 (class 0 OID 0)
-- Dependencies: 225
-- Name: COLUMN unified_programs.hierarchy_enabled; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.unified_programs.hierarchy_enabled IS 'Whether 7-level hierarchy is enabled for this program';


--
-- TOC entry 7096 (class 0 OID 0)
-- Dependencies: 225
-- Name: COLUMN unified_programs.hierarchy_depth; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.unified_programs.hierarchy_depth IS 'Number of hierarchy levels to use (1-7)';


--
-- TOC entry 7097 (class 0 OID 0)
-- Dependencies: 225
-- Name: COLUMN unified_programs.default_hierarchy_template; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.unified_programs.default_hierarchy_template IS 'Template: IHB_PROGRAM, COLLECTION_PROGRAM, WALLET_PROGRAM, etc.';


--
-- TOC entry 7098 (class 0 OID 0)
-- Dependencies: 225
-- Name: COLUMN unified_programs.viban_generation_strategy; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.unified_programs.viban_generation_strategy IS 'SEQUENTIAL, RANDOM, HIERARCHY_ENCODED';


--
-- TOC entry 7099 (class 0 OID 0)
-- Dependencies: 225
-- Name: COLUMN unified_programs.legal_entity_id; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.unified_programs.legal_entity_id IS 'Optional link to a specific legal entity that owns this program.';


--
-- TOC entry 264 (class 1259 OID 33535)
-- Name: unmatched_payments; Type: TABLE; Schema: public; Owner: vam_user
--

CREATE TABLE public.unmatched_payments (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    transaction_id uuid,
    viban_id uuid,
    virtual_account_id uuid,
    payment_reference character varying(100),
    payment_date timestamp without time zone NOT NULL,
    amount numeric(18,2) NOT NULL,
    currency_code character varying(3) NOT NULL,
    payer_name character varying(200),
    payer_account character varying(50),
    payer_bank character varying(100),
    remittance_info text,
    suggested_receivable_ids jsonb,
    match_attempts integer DEFAULT 0,
    last_match_attempt_at timestamp without time zone,
    status character varying(30) DEFAULT 'PENDING'::character varying NOT NULL,
    resolved_at timestamp without time zone,
    resolved_by character varying(100),
    resolution_type character varying(30),
    resolution_notes text,
    matched_receivable_id uuid,
    matched_payment_id uuid,
    return_reference character varying(100),
    return_date timestamp without time zone,
    return_reason character varying(500),
    escalated_at timestamp without time zone,
    escalated_to character varying(100),
    escalation_reason character varying(500),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    program_id uuid,
    bank_reference character varying(100),
    payer_bank_bic character varying(11),
    best_match_confidence integer,
    priority character varying(20) DEFAULT 'NORMAL'::character varying,
    exception_transaction_id uuid,
    exception_reason character varying(500),
    moved_to_exception_at timestamp without time zone,
    created_by character varying(255),
    updated_by character varying(255),
    version bigint
);


ALTER TABLE public.unmatched_payments OWNER TO vam_user;

--
-- TOC entry 7100 (class 0 OID 0)
-- Dependencies: 264
-- Name: TABLE unmatched_payments; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON TABLE public.unmatched_payments IS 'Payments that could not be automatically matched to receivables';


--
-- TOC entry 253 (class 1259 OID 32940)
-- Name: v_bank_relationship_summary; Type: VIEW; Schema: public; Owner: postgres
--

CREATE VIEW public.v_bank_relationship_summary AS
 SELECT bank_relationship,
    count(*) AS account_count,
    count(DISTINCT bank_code) AS bank_count,
    sum(current_balance) AS total_balance,
    array_agg(DISTINCT currency_code) AS currencies,
    array_agg(DISTINCT data_source) AS data_sources
   FROM public.physical_accounts pa
  WHERE ((status)::text = 'ACTIVE'::text)
  GROUP BY bank_relationship;


ALTER VIEW public.v_bank_relationship_summary OWNER TO postgres;

--
-- TOC entry 292 (class 1259 OID 50892)
-- Name: v_credit_limits_summary; Type: VIEW; Schema: public; Owner: postgres
--

CREATE VIEW public.v_credit_limits_summary AS
 SELECT cl.id,
    cl.corporate_id,
    cl.limit_name,
    cl.limit_type,
    cl.target_type,
    cl.target_id,
    cl.parent_limit_id,
    cl.limit_amount,
    cl.limit_currency,
    cl.utilized_amount,
    cl.available_amount,
    cl.held_amount,
    cl.allocated_to_children,
    cl.is_hard_limit,
    cl.requires_approval,
    cl.approval_threshold_percent,
    cl.warning_threshold_percent,
    cl.critical_threshold_percent,
    cl.status,
    cl.effective_from,
    cl.effective_to,
        CASE
            WHEN (cl.limit_amount > (0)::numeric) THEN round(((cl.utilized_amount / cl.limit_amount) * (100)::numeric), 2)
            ELSE (0)::numeric
        END AS utilization_percent,
        CASE
            WHEN ((cl.target_type)::text = 'CORPORATE'::text) THEN (cl.limit_amount - COALESCE(cl.allocated_to_children, (0)::numeric))
            ELSE NULL::numeric
        END AS unallocated_amount,
        CASE
            WHEN (cl.utilized_amount > cl.limit_amount) THEN true
            ELSE false
        END AS is_breached,
        CASE
            WHEN (((cl.utilized_amount / NULLIF(cl.limit_amount, (0)::numeric)) * (100)::numeric) >= cl.critical_threshold_percent) THEN true
            ELSE false
        END AS is_at_critical_level,
        CASE
            WHEN (((cl.utilized_amount / NULLIF(cl.limit_amount, (0)::numeric)) * (100)::numeric) >= cl.warning_threshold_percent) THEN true
            ELSE false
        END AS is_at_warning_level,
    le.entity_code,
    le.entity_name,
    le.country_code AS entity_country,
    cl.created_at,
    cl.updated_at
   FROM (public.credit_limits cl
     LEFT JOIN public.legal_entities le ON (((cl.target_id = le.id) AND ((cl.target_type)::text = 'LEGAL_ENTITY'::text))));


ALTER VIEW public.v_credit_limits_summary OWNER TO postgres;

--
-- TOC entry 7102 (class 0 OID 0)
-- Dependencies: 292
-- Name: VIEW v_credit_limits_summary; Type: COMMENT; Schema: public; Owner: postgres
--

COMMENT ON VIEW public.v_credit_limits_summary IS 'Summary view of credit limits with calculated fields and entity info';


--
-- TOC entry 227 (class 1259 OID 30726)
-- Name: virtual_accounts; Type: TABLE; Schema: public; Owner: vam_user
--

CREATE TABLE public.virtual_accounts (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone,
    created_by character varying(255),
    updated_at timestamp(6) without time zone,
    updated_by character varying(255),
    version bigint DEFAULT 0 NOT NULL,
    available_balance numeric(18,2),
    corporate_id uuid NOT NULL,
    currency_code character varying(3) NOT NULL,
    current_balance numeric(18,2),
    external_reference character varying(255),
    kyc_verified boolean,
    metadata jsonb,
    physical_account_id uuid,
    program_id uuid,
    status character varying(255),
    va_name character varying(255) NOT NULL,
    va_number character varying(255) NOT NULL,
    viban character varying(255),
    wallet_type character varying(255),
    activated_at timestamp(6) without time zone,
    allow_topup boolean,
    allow_transfer boolean,
    allow_withdrawal boolean,
    blocked_balance numeric(18,2),
    closed_at timestamp(6) without time zone,
    daily_limit numeric(18,2),
    daily_spent numeric(18,2),
    daily_topup_limit numeric(18,2),
    daily_topup_used numeric(18,2),
    expires_at date,
    holder_party_id uuid,
    kyc_level integer,
    kyc_verified_at timestamp(6) without time zone,
    last_limit_reset_date date,
    last_topup_at timestamp(6) without time zone,
    last_transaction_at timestamp(6) without time zone,
    last_withdrawal_at timestamp(6) without time zone,
    max_balance numeric(18,2),
    max_topup numeric(18,2),
    min_topup numeric(18,2),
    monthly_limit numeric(18,2),
    monthly_spent numeric(18,2),
    monthly_topup_limit numeric(18,2),
    monthly_topup_used numeric(18,2),
    pending_balance numeric(18,2),
    per_transaction_limit numeric(18,2),
    status_reason character varying(255),
    topup_count integer,
    transaction_count integer,
    transfer_count integer,
    weekly_limit numeric(18,2),
    weekly_spent numeric(18,2),
    withdrawal_count integer,
    yearly_limit numeric(18,2),
    yearly_spent numeric(18,2),
    hierarchy_node_id uuid,
    primary_viban_id uuid,
    hierarchy_path character varying(500),
    collection_channel character varying(20),
    value_type character varying(20) DEFAULT 'FIAT'::character varying,
    points_to_currency_rate numeric(10,4),
    transaction_limit numeric(18,2),
    annual_limit numeric(18,2),
    daily_used numeric(18,2) DEFAULT 0,
    monthly_used numeric(18,2) DEFAULT 0,
    annual_used numeric(18,2) DEFAULT 0,
    monthly_reset_day integer DEFAULT 1,
    mcc_whitelist jsonb,
    mcc_blacklist jsonb,
    merchant_whitelist jsonb,
    merchant_blacklist jsonb,
    country_whitelist jsonb,
    country_blacklist jsonb,
    balance_expiry_date date,
    expiry_action character varying(20) DEFAULT 'ZERO_BALANCE'::character varying,
    loyalty_tier character varying(20),
    loyalty_program_id uuid,
    points_balance numeric(18,2) DEFAULT 0,
    pending_points numeric(18,2) DEFAULT 0,
    lifetime_points numeric(18,2) DEFAULT 0,
    linked_card_id uuid,
    card_program_type character varying(30),
    budget_owner_id uuid,
    cost_center character varying(50),
    department character varying(100),
    block_reason character varying(255),
    held_balance numeric(18,2),
    kyc_expiry_date date,
    last_activity_date date,
    suspension_reason character varying(255),
    wallet_expiry_date date,
    weekly_used numeric(18,2),
    special_type character varying(20) DEFAULT 'REGULAR'::character varying,
    account_type character varying(20) DEFAULT 'VIRTUAL'::character varying,
    account_category character varying(30) DEFAULT 'TRANSACTION'::character varying,
    parent_account_id uuid,
    hierarchy_level integer DEFAULT 0,
    hierarchy_path_va character varying(1000),
    owning_entity_id uuid,
    owning_entity_code character varying(20),
    base_currency character varying(3),
    aggregated_balance numeric(19,4) DEFAULT 0,
    aggregated_balance_base numeric(19,4) DEFAULT 0,
    mirror_balance numeric(19,4) DEFAULT 0,
    fx_rate numeric(18,8),
    fx_rate_at timestamp without time zone,
    fx_rate_source character varying(50),
    balance_in_base numeric(19,4),
    linked_physical_account_id uuid,
    bank_balance numeric(19,4),
    bank_available_balance numeric(19,4),
    bank_balance_at timestamp without time zone,
    bank_account_number character varying(50),
    bank_iban character varying(34),
    bank_swift character varying(11),
    bank_name character varying(100),
    balance_data_source character varying(30),
    external_limit_id uuid,
    internal_limit_id uuid,
    effective_credit_limit numeric(19,4),
    credit_limit_utilized numeric(19,4) DEFAULT 0,
    credit_limit_available numeric(19,4),
    external_interest_config_id uuid,
    internal_interest_config_id uuid,
    effective_credit_rate numeric(8,5),
    effective_debit_rate numeric(8,5),
    ihb_sweep_enabled boolean,
    ihb_sweep_frequency character varying(20),
    target_cash_balance numeric(19,4),
    CONSTRAINT chk_va_card_program_type CHECK (((card_program_type IS NULL) OR ((card_program_type)::text = ANY ((ARRAY['TRAVEL'::character varying, 'PROCUREMENT'::character varying, 'FLEET'::character varying, 'VIRTUAL'::character varying, 'EXPENSE'::character varying, 'PETTY_CASH'::character varying])::text[])))),
    CONSTRAINT chk_va_collection_channel CHECK (((collection_channel IS NULL) OR ((collection_channel)::text = ANY ((ARRAY['INVOICE'::character varying, 'ECOMMERCE'::character varying, 'POS'::character varying, 'DIRECT'::character varying, 'ESCROW'::character varying, 'WALLET'::character varying, 'SUBSCRIPTION'::character varying])::text[])))),
    CONSTRAINT chk_va_expiry_action CHECK (((expiry_action IS NULL) OR ((expiry_action)::text = ANY ((ARRAY['ZERO_BALANCE'::character varying, 'FORFEIT'::character varying, 'TRANSFER'::character varying, 'EXTEND'::character varying, 'NOTIFY'::character varying])::text[])))),
    CONSTRAINT chk_va_special_type CHECK (((special_type)::text = ANY ((ARRAY['REGULAR'::character varying, 'SETTLEMENT'::character varying, 'EXCEPTION'::character varying])::text[]))),
    CONSTRAINT chk_va_value_type CHECK (((value_type IS NULL) OR ((value_type)::text = ANY ((ARRAY['FIAT'::character varying, 'POINTS'::character varying, 'MILES'::character varying, 'TOKENS'::character varying, 'CRYPTO'::character varying])::text[])))),
    CONSTRAINT virtual_accounts_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'INACTIVE'::character varying, 'SUSPENDED'::character varying, 'CLOSED'::character varying])::text[])))
);


ALTER TABLE public.virtual_accounts OWNER TO vam_user;

--
-- TOC entry 7104 (class 0 OID 0)
-- Dependencies: 227
-- Name: COLUMN virtual_accounts.physical_account_id; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.virtual_accounts.physical_account_id IS 'Physical account ID. Required for operational accounts (TRANSACTION, PHYSICAL_MIRROR, COLLECTION, DISBURSEMENT). 
 Nullable for consolidation accounts (ROOT, AGGREGATION, CURRENCY_MIRROR, EXCEPTION).';


--
-- TOC entry 7105 (class 0 OID 0)
-- Dependencies: 227
-- Name: COLUMN virtual_accounts.hierarchy_node_id; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.virtual_accounts.hierarchy_node_id IS 'Link to HierarchyNode (CONSOLIDATION type). Multiple VAs can attach to the same node (one per currency as Currency Mirror, plus transaction VAs).';


--
-- TOC entry 7106 (class 0 OID 0)
-- Dependencies: 227
-- Name: COLUMN virtual_accounts.primary_viban_id; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.virtual_accounts.primary_viban_id IS 'Primary VIBAN for this VA (for backward compatibility)';


--
-- TOC entry 7107 (class 0 OID 0)
-- Dependencies: 227
-- Name: COLUMN virtual_accounts.hierarchy_path; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.virtual_accounts.hierarchy_path IS 'Denormalized hierarchy path for quick queries';


--
-- TOC entry 7108 (class 0 OID 0)
-- Dependencies: 227
-- Name: COLUMN virtual_accounts.collection_channel; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.virtual_accounts.collection_channel IS 'Collection channel: INVOICE, ECOMMERCE, POS, etc.';


--
-- TOC entry 7109 (class 0 OID 0)
-- Dependencies: 227
-- Name: COLUMN virtual_accounts.value_type; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.virtual_accounts.value_type IS 'Type of value: FIAT, POINTS, MILES, TOKENS';


--
-- TOC entry 7110 (class 0 OID 0)
-- Dependencies: 227
-- Name: COLUMN virtual_accounts.mcc_whitelist; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.virtual_accounts.mcc_whitelist IS 'JSON array of allowed MCC codes for card spending';


--
-- TOC entry 7111 (class 0 OID 0)
-- Dependencies: 227
-- Name: COLUMN virtual_accounts.mcc_blacklist; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.virtual_accounts.mcc_blacklist IS 'JSON array of blocked MCC codes for card spending';


--
-- TOC entry 7112 (class 0 OID 0)
-- Dependencies: 227
-- Name: COLUMN virtual_accounts.balance_expiry_date; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.virtual_accounts.balance_expiry_date IS 'Date when balance expires (gift cards, points)';


--
-- TOC entry 7113 (class 0 OID 0)
-- Dependencies: 227
-- Name: COLUMN virtual_accounts.loyalty_tier; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.virtual_accounts.loyalty_tier IS 'Loyalty tier: PLATINUM, GOLD, SILVER, BLUE';


--
-- TOC entry 7114 (class 0 OID 0)
-- Dependencies: 227
-- Name: COLUMN virtual_accounts.special_type; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.virtual_accounts.special_type IS 'VA Special Type:
- REGULAR: Normal operational VA (default)
- SETTLEMENT: Contra-entry VA for fees/charges (CFO creates manually)
- EXCEPTION: Suspense VA for unmatched transactions (system auto-creates)';


--
-- TOC entry 7115 (class 0 OID 0)
-- Dependencies: 227
-- Name: COLUMN virtual_accounts.account_type; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.virtual_accounts.account_type IS 'REAL (physical mirror) or VIRTUAL';


--
-- TOC entry 7116 (class 0 OID 0)
-- Dependencies: 227
-- Name: COLUMN virtual_accounts.account_category; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.virtual_accounts.account_category IS 'Role of this VA: TRANSACTION (leaf), CURRENCY_MIRROR (aggregation by currency), EXCEPTION (suspense), SETTLEMENT (contra-entry), COLLECTION, DISBURSEMENT, WALLET, ESCROW.';


--
-- TOC entry 7117 (class 0 OID 0)
-- Dependencies: 227
-- Name: COLUMN virtual_accounts.parent_account_id; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.virtual_accounts.parent_account_id IS 'Parent VA for self-referencing hierarchy. Transaction VAs point to their Currency Mirror VA. Currency Mirrors point to parent level Currency Mirrors.';


--
-- TOC entry 7118 (class 0 OID 0)
-- Dependencies: 227
-- Name: COLUMN virtual_accounts.hierarchy_level; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.virtual_accounts.hierarchy_level IS 'Level in hierarchy (0=ROOT, 1=Shadow, etc.)';


--
-- TOC entry 7119 (class 0 OID 0)
-- Dependencies: 227
-- Name: COLUMN virtual_accounts.hierarchy_path_va; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.virtual_accounts.hierarchy_path_va IS 'Materialized path: /ROOT/SHADOW-EUR/MIRROR-EUR/VA-001';


--
-- TOC entry 7120 (class 0 OID 0)
-- Dependencies: 227
-- Name: COLUMN virtual_accounts.owning_entity_id; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.virtual_accounts.owning_entity_id IS 'Owning legal entity (subsidiary/branch) ID';


--
-- TOC entry 7121 (class 0 OID 0)
-- Dependencies: 227
-- Name: COLUMN virtual_accounts.owning_entity_code; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.virtual_accounts.owning_entity_code IS 'Denormalized entity code for display';


--
-- TOC entry 7122 (class 0 OID 0)
-- Dependencies: 227
-- Name: COLUMN virtual_accounts.base_currency; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.virtual_accounts.base_currency IS 'Base currency for aggregation (e.g., EUR)';


--
-- TOC entry 7123 (class 0 OID 0)
-- Dependencies: 227
-- Name: COLUMN virtual_accounts.aggregated_balance; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.virtual_accounts.aggregated_balance IS 'Sum of child balances (original currencies)';


--
-- TOC entry 7124 (class 0 OID 0)
-- Dependencies: 227
-- Name: COLUMN virtual_accounts.aggregated_balance_base; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.virtual_accounts.aggregated_balance_base IS 'Sum of child balances in base currency';


--
-- TOC entry 7125 (class 0 OID 0)
-- Dependencies: 227
-- Name: COLUMN virtual_accounts.mirror_balance; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.virtual_accounts.mirror_balance IS 'For CURRENCY_MIRROR accounts: aggregated balance of all child VAs in this currency.';


--
-- TOC entry 7126 (class 0 OID 0)
-- Dependencies: 227
-- Name: COLUMN virtual_accounts.fx_rate; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.virtual_accounts.fx_rate IS 'FX rate to convert to base currency';


--
-- TOC entry 7127 (class 0 OID 0)
-- Dependencies: 227
-- Name: COLUMN virtual_accounts.fx_rate_at; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.virtual_accounts.fx_rate_at IS 'When FX rate was last updated';


--
-- TOC entry 7128 (class 0 OID 0)
-- Dependencies: 227
-- Name: COLUMN virtual_accounts.fx_rate_source; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.virtual_accounts.fx_rate_source IS 'Source: REUTERS, BLOOMBERG, CBS, MANUAL';


--
-- TOC entry 7129 (class 0 OID 0)
-- Dependencies: 227
-- Name: COLUMN virtual_accounts.balance_in_base; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.virtual_accounts.balance_in_base IS 'For CURRENCY_MIRROR accounts: mirror_balance converted to base currency using fx_rate.';


--
-- TOC entry 7130 (class 0 OID 0)
-- Dependencies: 227
-- Name: COLUMN virtual_accounts.linked_physical_account_id; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.virtual_accounts.linked_physical_account_id IS 'Link to physical_accounts table';


--
-- TOC entry 7131 (class 0 OID 0)
-- Dependencies: 227
-- Name: COLUMN virtual_accounts.bank_balance; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.virtual_accounts.bank_balance IS 'Current balance from CBS (real bank balance)';


--
-- TOC entry 7132 (class 0 OID 0)
-- Dependencies: 227
-- Name: COLUMN virtual_accounts.bank_available_balance; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.virtual_accounts.bank_available_balance IS 'Available balance from CBS';


--
-- TOC entry 7133 (class 0 OID 0)
-- Dependencies: 227
-- Name: COLUMN virtual_accounts.bank_balance_at; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.virtual_accounts.bank_balance_at IS 'When bank balance was last synced';


--
-- TOC entry 7134 (class 0 OID 0)
-- Dependencies: 227
-- Name: COLUMN virtual_accounts.bank_account_number; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.virtual_accounts.bank_account_number IS 'Bank account number for display/matching';


--
-- TOC entry 7135 (class 0 OID 0)
-- Dependencies: 227
-- Name: COLUMN virtual_accounts.balance_data_source; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.virtual_accounts.balance_data_source IS 'How balance is synced: CORE_BANKING, SWIFT_MT940, etc.';


--
-- TOC entry 7136 (class 0 OID 0)
-- Dependencies: 227
-- Name: COLUMN virtual_accounts.external_limit_id; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.virtual_accounts.external_limit_id IS 'External credit limit ID (from CBS via CreditFacility)';


--
-- TOC entry 7137 (class 0 OID 0)
-- Dependencies: 227
-- Name: COLUMN virtual_accounts.internal_limit_id; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.virtual_accounts.internal_limit_id IS 'Internal credit limit ID (allocated by CFO)';


--
-- TOC entry 7138 (class 0 OID 0)
-- Dependencies: 227
-- Name: COLUMN virtual_accounts.effective_credit_limit; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.virtual_accounts.effective_credit_limit IS 'Effective combined credit limit';


--
-- TOC entry 7139 (class 0 OID 0)
-- Dependencies: 227
-- Name: COLUMN virtual_accounts.credit_limit_utilized; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.virtual_accounts.credit_limit_utilized IS 'Amount of credit limit currently used';


--
-- TOC entry 7140 (class 0 OID 0)
-- Dependencies: 227
-- Name: COLUMN virtual_accounts.credit_limit_available; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.virtual_accounts.credit_limit_available IS 'Available credit limit (effective - utilized)';


--
-- TOC entry 7141 (class 0 OID 0)
-- Dependencies: 227
-- Name: COLUMN virtual_accounts.external_interest_config_id; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.virtual_accounts.external_interest_config_id IS 'External interest config ID (bank rates)';


--
-- TOC entry 7142 (class 0 OID 0)
-- Dependencies: 227
-- Name: COLUMN virtual_accounts.internal_interest_config_id; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.virtual_accounts.internal_interest_config_id IS 'Internal interest config ID (treasury rates)';


--
-- TOC entry 7143 (class 0 OID 0)
-- Dependencies: 227
-- Name: COLUMN virtual_accounts.effective_credit_rate; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.virtual_accounts.effective_credit_rate IS 'Effective credit interest rate (positive balances)';


--
-- TOC entry 7144 (class 0 OID 0)
-- Dependencies: 227
-- Name: COLUMN virtual_accounts.effective_debit_rate; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.virtual_accounts.effective_debit_rate IS 'Effective debit interest rate (overdrafts)';


--
-- TOC entry 7145 (class 0 OID 0)
-- Dependencies: 227
-- Name: COLUMN virtual_accounts.ihb_sweep_enabled; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.virtual_accounts.ihb_sweep_enabled IS 'Whether this account participates in IHB sweeping';


--
-- TOC entry 7146 (class 0 OID 0)
-- Dependencies: 227
-- Name: COLUMN virtual_accounts.ihb_sweep_frequency; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.virtual_accounts.ihb_sweep_frequency IS 'IHB sweep frequency: DAILY, REAL_TIME, WEEKLY';


--
-- TOC entry 7147 (class 0 OID 0)
-- Dependencies: 227
-- Name: COLUMN virtual_accounts.target_cash_balance; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.virtual_accounts.target_cash_balance IS 'Target cash balance for IHB sweeping. 0 = sweep all surplus/deficit';


--
-- TOC entry 293 (class 1259 OID 50909)
-- Name: v_currency_mirror_hierarchy; Type: VIEW; Schema: public; Owner: postgres
--

CREATE VIEW public.v_currency_mirror_hierarchy AS
 SELECT m.id AS mirror_id,
    m.va_number AS mirror_code,
    m.currency_code,
    m.hierarchy_level,
    m.mirror_balance,
    m.fx_rate,
    m.balance_in_base,
    m.program_id,
    n.node_code,
    n.node_name,
    n.materialized_path,
    pm.va_number AS parent_mirror_code,
    ( SELECT count(*) AS count
           FROM public.virtual_accounts c
          WHERE ((c.parent_account_id = m.id) AND ((c.account_category)::text = 'CURRENCY_MIRROR'::text))) AS child_mirror_count,
    ( SELECT count(*) AS count
           FROM public.virtual_accounts c
          WHERE ((c.parent_account_id = m.id) AND ((c.account_category)::text <> 'CURRENCY_MIRROR'::text))) AS child_va_count
   FROM ((public.virtual_accounts m
     JOIN public.hierarchy_nodes n ON ((m.hierarchy_node_id = n.id)))
     LEFT JOIN public.virtual_accounts pm ON ((m.parent_account_id = pm.id)))
  WHERE ((m.account_category)::text = 'CURRENCY_MIRROR'::text)
  ORDER BY m.program_id, m.currency_code, m.hierarchy_level;


ALTER VIEW public.v_currency_mirror_hierarchy OWNER TO postgres;

--
-- TOC entry 7148 (class 0 OID 0)
-- Dependencies: 293
-- Name: VIEW v_currency_mirror_hierarchy; Type: COMMENT; Schema: public; Owner: postgres
--

COMMENT ON VIEW public.v_currency_mirror_hierarchy IS 'View showing Currency Mirror VA hierarchy with parent-child relationships and balance information.';


--
-- TOC entry 298 (class 1259 OID 51123)
-- Name: v_entity_ihb_positions; Type: VIEW; Schema: public; Owner: postgres
--

CREATE VIEW public.v_entity_ihb_positions AS
 SELECT le.id AS entity_id,
    le.entity_code,
    le.entity_name,
    le.corporate_id,
    le.functional_currency,
    COALESCE(lender.total_lent, (0)::numeric) AS total_lent,
    COALESCE(lender.active_loans_as_lender, (0)::bigint) AS active_loans_as_lender,
    COALESCE(borrower.total_borrowed, (0)::numeric) AS total_borrowed,
    COALESCE(borrower.active_loans_as_borrower, (0)::bigint) AS active_loans_as_borrower,
    COALESCE(deposits.total_deposited, (0)::numeric) AS total_deposited,
    COALESCE(deposits.active_deposits, (0)::bigint) AS active_deposits,
    ((COALESCE(lender.total_lent, (0)::numeric) + COALESCE(deposits.total_deposited, (0)::numeric)) - COALESCE(borrower.total_borrowed, (0)::numeric)) AS net_position,
    le.ihb_credit_limit,
    le.ihb_current_exposure,
    le.ihb_available_limit
   FROM (((public.legal_entities le
     LEFT JOIN ( SELECT ihb_loans.lender_legal_entity_id,
            sum(ihb_loans.outstanding_amount) AS total_lent,
            count(*) AS active_loans_as_lender
           FROM public.ihb_loans
          WHERE (((ihb_loans.status)::text = 'ACTIVE'::text) AND (ihb_loans.lender_legal_entity_id IS NOT NULL))
          GROUP BY ihb_loans.lender_legal_entity_id) lender ON ((le.id = lender.lender_legal_entity_id)))
     LEFT JOIN ( SELECT ihb_loans.borrower_legal_entity_id,
            sum(ihb_loans.outstanding_amount) AS total_borrowed,
            count(*) AS active_loans_as_borrower
           FROM public.ihb_loans
          WHERE (((ihb_loans.status)::text = 'ACTIVE'::text) AND (ihb_loans.borrower_legal_entity_id IS NOT NULL))
          GROUP BY ihb_loans.borrower_legal_entity_id) borrower ON ((le.id = borrower.borrower_legal_entity_id)))
     LEFT JOIN ( SELECT ihb_deposits.depositor_legal_entity_id,
            sum(ihb_deposits.current_balance) AS total_deposited,
            count(*) AS active_deposits
           FROM public.ihb_deposits
          WHERE (((ihb_deposits.status)::text = 'ACTIVE'::text) AND (ihb_deposits.depositor_legal_entity_id IS NOT NULL))
          GROUP BY ihb_deposits.depositor_legal_entity_id) deposits ON ((le.id = deposits.depositor_legal_entity_id)))
  WHERE (le.ihb_enabled = true);


ALTER VIEW public.v_entity_ihb_positions OWNER TO postgres;

--
-- TOC entry 7150 (class 0 OID 0)
-- Dependencies: 298
-- Name: VIEW v_entity_ihb_positions; Type: COMMENT; Schema: public; Owner: postgres
--

COMMENT ON VIEW public.v_entity_ihb_positions IS 'Consolidated IHB position view for each entity showing loans, deposits, and net position';


--
-- TOC entry 295 (class 1259 OID 51052)
-- Name: v_latest_fx_rates; Type: VIEW; Schema: public; Owner: postgres
--

CREATE VIEW public.v_latest_fx_rates AS
 SELECT DISTINCT ON (from_currency, to_currency) id,
    from_currency,
    to_currency,
    rate,
    bid_rate,
    ask_rate,
    rate_date,
    source,
    spread_bps,
    is_active
   FROM public.fx_rates
  WHERE (is_active = true)
  ORDER BY from_currency, to_currency, rate_date DESC;


ALTER VIEW public.v_latest_fx_rates OWNER TO postgres;

--
-- TOC entry 7152 (class 0 OID 0)
-- Dependencies: 295
-- Name: VIEW v_latest_fx_rates; Type: COMMENT; Schema: public; Owner: postgres
--

COMMENT ON VIEW public.v_latest_fx_rates IS 'Latest FX rates for each currency pair';


--
-- TOC entry 296 (class 1259 OID 51056)
-- Name: v_fx_rate_matrix; Type: VIEW; Schema: public; Owner: postgres
--

CREATE VIEW public.v_fx_rate_matrix AS
 SELECT from_currency,
    max(
        CASE
            WHEN ((to_currency)::text = 'AED'::text) THEN rate
            ELSE NULL::numeric
        END) AS to_aed,
    max(
        CASE
            WHEN ((to_currency)::text = 'USD'::text) THEN rate
            ELSE NULL::numeric
        END) AS to_usd,
    max(
        CASE
            WHEN ((to_currency)::text = 'EUR'::text) THEN rate
            ELSE NULL::numeric
        END) AS to_eur,
    max(
        CASE
            WHEN ((to_currency)::text = 'GBP'::text) THEN rate
            ELSE NULL::numeric
        END) AS to_gbp,
    max(
        CASE
            WHEN ((to_currency)::text = 'SAR'::text) THEN rate
            ELSE NULL::numeric
        END) AS to_sar,
    max(rate_date) AS rate_date
   FROM public.v_latest_fx_rates
  GROUP BY from_currency
  ORDER BY from_currency;


ALTER VIEW public.v_fx_rate_matrix OWNER TO postgres;

--
-- TOC entry 7154 (class 0 OID 0)
-- Dependencies: 296
-- Name: VIEW v_fx_rate_matrix; Type: COMMENT; Schema: public; Owner: postgres
--

COMMENT ON VIEW public.v_fx_rate_matrix IS 'FX rate matrix for common currencies';


--
-- TOC entry 297 (class 1259 OID 51118)
-- Name: v_ihb_entities; Type: VIEW; Schema: public; Owner: postgres
--

CREATE VIEW public.v_ihb_entities AS
 SELECT id,
    entity_code,
    entity_name,
    short_name,
    entity_type,
    corporate_id,
    functional_currency,
    ihb_enabled,
    ihb_credit_limit,
    ihb_current_exposure,
    ihb_available_limit,
    lending_rate_spread,
    borrowing_rate_spread,
    can_lend,
    can_borrow,
    settlement_va_id,
    ihb_currency,
    total_lent_out,
    total_deposited,
    net_ihb_position,
    is_treasury_center,
    contact_email,
    contact_phone,
    status,
        CASE
            WHEN ((ihb_credit_limit IS NOT NULL) AND (ihb_credit_limit > (0)::numeric)) THEN round(((ihb_current_exposure / ihb_credit_limit) * (100)::numeric), 2)
            ELSE (0)::numeric
        END AS utilization_percent,
        CASE
            WHEN ((ihb_credit_limit IS NOT NULL) AND (ihb_current_exposure >= (ihb_credit_limit * 0.8))) THEN true
            ELSE false
        END AS limit_warning,
        CASE
            WHEN ((ihb_credit_limit IS NOT NULL) AND (ihb_current_exposure >= ihb_credit_limit)) THEN true
            ELSE false
        END AS limit_breached
   FROM public.legal_entities le
  WHERE ((ihb_enabled = true) AND ((status)::text = 'ACTIVE'::text));


ALTER VIEW public.v_ihb_entities OWNER TO postgres;

--
-- TOC entry 7156 (class 0 OID 0)
-- Dependencies: 297
-- Name: VIEW v_ihb_entities; Type: COMMENT; Schema: public; Owner: postgres
--

COMMENT ON VIEW public.v_ihb_entities IS 'View of legal entities enabled for In-House Banking with computed position metrics';


--
-- TOC entry 252 (class 1259 OID 32935)
-- Name: v_physical_account_summary; Type: VIEW; Schema: public; Owner: postgres
--

CREATE VIEW public.v_physical_account_summary AS
 SELECT bank_code,
    bank_name,
    bank_country,
    bank_relationship,
    count(*) AS account_count,
    sum(current_balance) AS total_balance,
    sum(available_balance) AS total_available,
    array_agg(DISTINCT currency_code) AS currencies,
    count(
        CASE
            WHEN pooling_enabled THEN 1
            ELSE NULL::integer
        END) AS pooling_count,
    count(
        CASE
            WHEN sweep_enabled THEN 1
            ELSE NULL::integer
        END) AS sweep_count,
    count(
        CASE
            WHEN can_host_virtual_accounts THEN 1
            ELSE NULL::integer
        END) AS va_host_count,
    sum(COALESCE(virtual_account_count, 0)) AS total_virtual_accounts
   FROM public.physical_accounts pa
  WHERE ((status)::text = 'ACTIVE'::text)
  GROUP BY bank_code, bank_name, bank_country, bank_relationship;


ALTER VIEW public.v_physical_account_summary OWNER TO postgres;

--
-- TOC entry 285 (class 1259 OID 42318)
-- Name: v_program_effective_charges; Type: VIEW; Schema: public; Owner: postgres
--

CREATE VIEW public.v_program_effective_charges AS
 SELECT p.id AS program_id,
    p.program_code,
    p.program_name,
    cc.charge_code,
    cc.charge_name,
    cc.charge_type,
    cc.charge_category,
    cc.percentage_rate AS base_percentage,
    cc.fixed_amount AS base_fixed,
    cc.minimum_charge AS base_minimum,
    cc.maximum_charge AS base_maximum,
    pco.override_percentage,
    pco.override_fixed,
    pco.override_minimum,
    pco.override_maximum,
    pco.is_waived,
    COALESCE(pco.override_percentage, cc.percentage_rate) AS effective_percentage,
    COALESCE(pco.override_fixed, cc.fixed_amount) AS effective_fixed,
    COALESCE(pco.override_minimum, cc.minimum_charge) AS effective_minimum,
    COALESCE(pco.override_maximum, cc.maximum_charge) AS effective_maximum,
        CASE
            WHEN (pco.id IS NOT NULL) THEN true
            ELSE false
        END AS has_override,
    cc.status AS config_status,
    pco.status AS override_status
   FROM ((public.unified_programs p
     CROSS JOIN public.charge_configurations cc)
     LEFT JOIN public.program_charge_overrides pco ON (((pco.program_id = p.id) AND ((pco.charge_code)::text = (cc.charge_code)::text) AND ((pco.status)::text = 'ACTIVE'::text) AND ((pco.effective_to IS NULL) OR (pco.effective_to >= CURRENT_DATE)))))
  WHERE (((cc.status)::text = 'ACTIVE'::text) AND ((cc.effective_to IS NULL) OR (cc.effective_to >= CURRENT_DATE)));


ALTER VIEW public.v_program_effective_charges OWNER TO postgres;

--
-- TOC entry 7159 (class 0 OID 0)
-- Dependencies: 285
-- Name: VIEW v_program_effective_charges; Type: COMMENT; Schema: public; Owner: postgres
--

COMMENT ON VIEW public.v_program_effective_charges IS 'Consolidated view of charges with program overrides applied';


--
-- TOC entry 226 (class 1259 OID 30710)
-- Name: va_movements; Type: TABLE; Schema: public; Owner: vam_user
--

CREATE TABLE public.va_movements (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone,
    created_by character varying(255),
    updated_at timestamp(6) without time zone,
    updated_by character varying(255),
    version bigint,
    amount numeric(18,2) NOT NULL,
    balance_after numeric(18,2),
    balance_before numeric(18,2),
    bancs_reference character varying(255),
    beneficiary_account character varying(255),
    beneficiary_name character varying(255),
    channel character varying(255),
    counterparty_va_id uuid,
    currency_code character varying(3) NOT NULL,
    description character varying(255),
    external_reference character varying(255),
    movement_type character varying(255) NOT NULL,
    physical_account_id uuid NOT NULL,
    reference_number character varying(255),
    remitter_account character varying(255),
    remitter_name character varying(255),
    status character varying(255),
    transaction_date timestamp(6) without time zone NOT NULL,
    va_id uuid NOT NULL,
    value_date date,
    corporate_id uuid,
    approved_at timestamp(6) without time zone,
    approved_by character varying(255),
    authorization_code character varying(20),
    correlation_id character varying(255),
    destination_reference character varying(255),
    destination_type character varying(30),
    device_id character varying(255),
    fee_amount numeric(18,2),
    initiated_by character varying(255),
    ip_address character varying(50),
    merchant_category character varying(10),
    merchant_id character varying(30),
    merchant_name character varying(255),
    metadata jsonb,
    net_amount numeric(18,2),
    original_transaction_id uuid,
    program_id uuid,
    reversal_reference character varying(255),
    reversed_at timestamp(6) without time zone,
    source_reference character varying(255),
    source_type character varying(30),
    terminal_id character varying(20),
    viban_id uuid,
    viban character varying(34),
    routed_via_viban boolean DEFAULT false,
    auto_reconciled boolean DEFAULT false,
    reconciled_reference_type character varying(50),
    reconciled_reference_id character varying(100),
    reconciliation_match_type character varying(20),
    source_hierarchy_node_id uuid,
    target_hierarchy_node_id uuid,
    hierarchy_path character varying(500),
    is_pobo boolean DEFAULT false,
    is_robo boolean DEFAULT false,
    behalf_of_entity character varying(100),
    behalf_of_va_id uuid,
    match_confidence integer,
    processing_notes character varying(500),
    routing_time_ms integer,
    CONSTRAINT va_movements_movement_type_check CHECK (((movement_type)::text = ANY ((ARRAY['CREDIT'::character varying, 'DEBIT'::character varying, 'TRANSFER_IN'::character varying, 'TRANSFER_OUT'::character varying, 'REVERSAL'::character varying])::text[]))),
    CONSTRAINT va_movements_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying, 'REVERSED'::character varying, 'CANCELLED'::character varying])::text[])))
);


ALTER TABLE public.va_movements OWNER TO vam_user;

--
-- TOC entry 7161 (class 0 OID 0)
-- Dependencies: 226
-- Name: COLUMN va_movements.viban_id; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.va_movements.viban_id IS 'VIBAN used for this transaction (ROBO routing)';


--
-- TOC entry 7162 (class 0 OID 0)
-- Dependencies: 226
-- Name: COLUMN va_movements.viban; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.va_movements.viban IS 'VIBAN string for quick reference';


--
-- TOC entry 7163 (class 0 OID 0)
-- Dependencies: 226
-- Name: COLUMN va_movements.routed_via_viban; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.va_movements.routed_via_viban IS 'True if transaction was routed via VIBAN (ROBO)';


--
-- TOC entry 7164 (class 0 OID 0)
-- Dependencies: 226
-- Name: COLUMN va_movements.auto_reconciled; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.va_movements.auto_reconciled IS 'True if auto-reconciliation matched this transaction';


--
-- TOC entry 7165 (class 0 OID 0)
-- Dependencies: 226
-- Name: COLUMN va_movements.reconciled_reference_type; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.va_movements.reconciled_reference_type IS 'Type of matched entity: INVOICE, ORDER, etc.';


--
-- TOC entry 7166 (class 0 OID 0)
-- Dependencies: 226
-- Name: COLUMN va_movements.reconciled_reference_id; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.va_movements.reconciled_reference_id IS 'ID of matched entity';


--
-- TOC entry 7167 (class 0 OID 0)
-- Dependencies: 226
-- Name: COLUMN va_movements.is_pobo; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.va_movements.is_pobo IS 'Pay On Behalf Of - payment made for another entity';


--
-- TOC entry 7168 (class 0 OID 0)
-- Dependencies: 226
-- Name: COLUMN va_movements.is_robo; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.va_movements.is_robo IS 'Receive On Behalf Of - payment received for another entity';


--
-- TOC entry 7169 (class 0 OID 0)
-- Dependencies: 226
-- Name: COLUMN va_movements.behalf_of_entity; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.va_movements.behalf_of_entity IS 'Entity name on whose behalf transaction was made';


--
-- TOC entry 257 (class 1259 OID 33154)
-- Name: viban_pools; Type: TABLE; Schema: public; Owner: vam_user
--

CREATE TABLE public.viban_pools (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    program_id uuid NOT NULL,
    pool_name character varying(100) NOT NULL,
    pool_code character varying(20) NOT NULL,
    description character varying(500),
    country_code character varying(2) DEFAULT 'AE'::character varying NOT NULL,
    bank_code character varying(10) NOT NULL,
    prefix character varying(20) NOT NULL,
    suffix_length integer DEFAULT 10,
    check_digit_algorithm character varying(20) DEFAULT 'MOD97'::character varying,
    pool_size integer NOT NULL,
    available_count integer NOT NULL,
    assigned_count integer GENERATED ALWAYS AS ((pool_size - available_count)) STORED,
    reserved_count integer DEFAULT 0,
    assignment_ttl_minutes integer DEFAULT 1440,
    auto_return_expired boolean DEFAULT true,
    low_threshold_percent integer DEFAULT 20,
    alert_email character varying(255),
    status character varying(20) DEFAULT 'ACTIVE'::character varying,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by character varying(100),
    updated_by character varying(100),
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT chk_available_count CHECK ((available_count <= pool_size)),
    CONSTRAINT viban_pools_available_count_check CHECK ((available_count >= 0)),
    CONSTRAINT viban_pools_pool_size_check CHECK ((pool_size > 0)),
    CONSTRAINT viban_pools_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'INACTIVE'::character varying, 'EXHAUSTED'::character varying, 'SUSPENDED'::character varying])::text[])))
);


ALTER TABLE public.viban_pools OWNER TO vam_user;

--
-- TOC entry 7170 (class 0 OID 0)
-- Dependencies: 257
-- Name: TABLE viban_pools; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON TABLE public.viban_pools IS 'Pre-generated VIBAN pools for high-volume assignment';


--
-- TOC entry 7171 (class 0 OID 0)
-- Dependencies: 257
-- Name: COLUMN viban_pools.assignment_ttl_minutes; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.viban_pools.assignment_ttl_minutes IS 'Time-to-live in minutes before unmatched VIBANs return to pool';


--
-- TOC entry 7172 (class 0 OID 0)
-- Dependencies: 257
-- Name: COLUMN viban_pools.low_threshold_percent; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.viban_pools.low_threshold_percent IS 'Alert when available percentage falls below this threshold';


--
-- TOC entry 260 (class 1259 OID 33301)
-- Name: viban_sequences; Type: TABLE; Schema: public; Owner: vam_user
--

CREATE TABLE public.viban_sequences (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    pool_id uuid NOT NULL,
    prefix character varying(20) NOT NULL,
    current_value bigint DEFAULT 0,
    max_value bigint,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


ALTER TABLE public.viban_sequences OWNER TO vam_user;

--
-- TOC entry 259 (class 1259 OID 33257)
-- Name: viban_usage_log; Type: TABLE; Schema: public; Owner: vam_user
--

CREATE TABLE public.viban_usage_log (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    viban_id uuid NOT NULL,
    viban character varying(34) NOT NULL,
    virtual_account_id uuid NOT NULL,
    program_id uuid NOT NULL,
    transaction_id uuid,
    amount numeric(18,2) NOT NULL,
    currency_code character varying(3) NOT NULL,
    sender_name character varying(200),
    sender_account character varying(50),
    sender_bank_code character varying(20),
    sender_bank_name character varying(100),
    payment_reference character varying(100),
    end_to_end_reference character varying(100),
    remittance_info text,
    matched_reference_type character varying(50),
    matched_reference_id character varying(100),
    match_type character varying(20),
    match_confidence integer,
    match_reason character varying(255),
    routed_from_physical_account uuid,
    routing_time_ms integer,
    status character varying(20) DEFAULT 'RECEIVED'::character varying,
    received_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    processed_at timestamp without time zone,
    matched_at timestamp without time zone,
    error_code character varying(20),
    error_message character varying(500),
    CONSTRAINT viban_usage_log_match_confidence_check CHECK (((match_confidence >= 0) AND (match_confidence <= 100))),
    CONSTRAINT viban_usage_log_match_type_check CHECK (((match_type)::text = ANY ((ARRAY['AUTO'::character varying, 'MANUAL'::character varying, 'PARTIAL'::character varying, 'UNMATCHED'::character varying, 'REJECTED'::character varying])::text[]))),
    CONSTRAINT viban_usage_log_status_check CHECK (((status)::text = ANY ((ARRAY['RECEIVED'::character varying, 'PROCESSED'::character varying, 'MATCHED'::character varying, 'UNMATCHED'::character varying, 'RETURNED'::character varying, 'FAILED'::character varying])::text[])))
);


ALTER TABLE public.viban_usage_log OWNER TO vam_user;

--
-- TOC entry 7173 (class 0 OID 0)
-- Dependencies: 259
-- Name: TABLE viban_usage_log; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON TABLE public.viban_usage_log IS 'Audit log of all payments received via VIBANs for ROBO tracking';


--
-- TOC entry 258 (class 1259 OID 33196)
-- Name: vibans; Type: TABLE; Schema: public; Owner: vam_user
--

CREATE TABLE public.vibans (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    viban character varying(34) NOT NULL,
    virtual_account_id uuid,
    program_id uuid NOT NULL,
    hierarchy_node_id uuid,
    viban_type character varying(20) NOT NULL,
    is_primary boolean DEFAULT false,
    reference_type character varying(50),
    reference_id character varying(100),
    reference_description character varying(255),
    status character varying(20) DEFAULT 'ACTIVE'::character varying,
    valid_from timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    valid_until timestamp without time zone,
    single_use boolean DEFAULT false,
    times_used integer DEFAULT 0,
    total_amount_received numeric(18,2) DEFAULT 0,
    last_used_at timestamp without time zone,
    last_payment_amount numeric(18,2),
    expected_amount numeric(18,2),
    amount_tolerance_percent numeric(5,2) DEFAULT 0,
    amount_tolerance_fixed numeric(18,2),
    min_amount numeric(18,2),
    max_amount numeric(18,2),
    currency_code character varying(3),
    pool_id uuid,
    assigned_at timestamp without time zone,
    return_scheduled_at timestamp without time zone,
    customer_name character varying(200),
    customer_reference character varying(100),
    purpose character varying(500),
    payment_link character varying(500),
    qr_code_data text,
    metadata jsonb,
    tags character varying(255),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    created_by character varying(100),
    updated_by character varying(100),
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT chk_single_primary CHECK ((((is_primary = true) AND ((viban_type)::text = 'PRIMARY'::text)) OR (is_primary = false))),
    CONSTRAINT vibans_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'PAID'::character varying, 'PARTIAL'::character varying, 'EXPIRED'::character varying, 'CANCELLED'::character varying, 'SUSPENDED'::character varying, 'RETURNED'::character varying])::text[]))),
    CONSTRAINT vibans_viban_type_check CHECK (((viban_type)::text = ANY ((ARRAY['PRIMARY'::character varying, 'INVOICE'::character varying, 'ORDER'::character varying, 'CUSTOMER'::character varying, 'TERMINAL'::character varying, 'BATCH'::character varying, 'TEMPORARY'::character varying, 'SUBSCRIPTION'::character varying, 'POLICY'::character varying])::text[])))
);


ALTER TABLE public.vibans OWNER TO vam_user;

--
-- TOC entry 7174 (class 0 OID 0)
-- Dependencies: 258
-- Name: TABLE vibans; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON TABLE public.vibans IS 'Virtual IBANs with 1:N relationship to Virtual Accounts';


--
-- TOC entry 7175 (class 0 OID 0)
-- Dependencies: 258
-- Name: COLUMN vibans.viban; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.vibans.viban IS 'Unique virtual IBAN string, primary lookup key - must be indexed for <5ms lookup';


--
-- TOC entry 7176 (class 0 OID 0)
-- Dependencies: 258
-- Name: COLUMN vibans.is_primary; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.vibans.is_primary IS 'True if this is the main/default VIBAN for the VA';


--
-- TOC entry 7177 (class 0 OID 0)
-- Dependencies: 258
-- Name: COLUMN vibans.reference_type; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.vibans.reference_type IS 'Type of linked entity: INVOICE, ORDER, CUSTOMER, POLICY, etc.';


--
-- TOC entry 7178 (class 0 OID 0)
-- Dependencies: 258
-- Name: COLUMN vibans.reference_id; Type: COMMENT; Schema: public; Owner: vam_user
--

COMMENT ON COLUMN public.vibans.reference_id IS 'ID of linked entity for auto-reconciliation (ROBO)';


--
-- TOC entry 300 (class 1259 OID 51192)
-- Name: vw_intercompany_parties; Type: VIEW; Schema: public; Owner: postgres
--

CREATE VIEW public.vw_intercompany_parties AS
 SELECT id,
    party_code,
    legal_name,
    display_name,
    roles,
    owning_entity_id,
    owning_entity_code,
    is_intercompany,
    linked_legal_entity_id,
    linked_legal_entity_code,
    netting_eligible,
    ic_settlement_method,
    ic_credit_limit,
    ic_current_exposure,
    ic_currency,
    status,
    corporate_id,
        CASE
            WHEN ((ic_credit_limit IS NOT NULL) AND (ic_credit_limit > (0)::numeric)) THEN (ic_credit_limit - COALESCE(ic_current_exposure, (0)::numeric))
            ELSE NULL::numeric
        END AS available_credit,
        CASE
            WHEN ((ic_credit_limit IS NOT NULL) AND (ic_credit_limit > (0)::numeric)) THEN round(((COALESCE(ic_current_exposure, (0)::numeric) / ic_credit_limit) * (100)::numeric), 2)
            ELSE NULL::numeric
        END AS credit_utilization_pct
   FROM public.parties p
  WHERE ((is_intercompany = true) AND (linked_legal_entity_id IS NOT NULL) AND ((status)::text = 'ACTIVE'::text));


ALTER VIEW public.vw_intercompany_parties OWNER TO postgres;

--
-- TOC entry 7179 (class 0 OID 0)
-- Dependencies: 300
-- Name: VIEW vw_intercompany_parties; Type: COMMENT; Schema: public; Owner: postgres
--

COMMENT ON VIEW public.vw_intercompany_parties IS 'Intercompany parties representing group entities for IC transaction tracking.';


--
-- TOC entry 301 (class 1259 OID 51197)
-- Name: vw_netting_eligible_parties; Type: VIEW; Schema: public; Owner: postgres
--

CREATE VIEW public.vw_netting_eligible_parties AS
 SELECT id,
    party_code,
    legal_name,
    display_name,
    roles,
    owning_entity_id,
    owning_entity_code,
    is_intercompany,
    linked_legal_entity_id,
    linked_legal_entity_code,
    netting_eligible,
    ic_settlement_method,
    status,
    corporate_id,
        CASE
            WHEN (is_intercompany = true) THEN 'INTERCOMPANY'::text
            WHEN (netting_eligible = true) THEN 'EXPLICIT_NETTING'::text
            ELSE 'NOT_ELIGIBLE'::text
        END AS netting_reason
   FROM public.parties p
  WHERE (((is_intercompany = true) OR (netting_eligible = true)) AND ((status)::text = 'ACTIVE'::text));


ALTER VIEW public.vw_netting_eligible_parties OWNER TO postgres;

--
-- TOC entry 7181 (class 0 OID 0)
-- Dependencies: 301
-- Name: VIEW vw_netting_eligible_parties; Type: COMMENT; Schema: public; Owner: postgres
--

COMMENT ON VIEW public.vw_netting_eligible_parties IS 'All parties eligible for netting cycles (intercompany or explicitly enabled).';


--
-- TOC entry 299 (class 1259 OID 51187)
-- Name: vw_pobo_eligible_vendors; Type: VIEW; Schema: public; Owner: postgres
--

CREATE VIEW public.vw_pobo_eligible_vendors AS
 SELECT id,
    party_code,
    legal_name,
    display_name,
    tax_id,
    roles,
    owning_entity_id,
    owning_entity_code,
    pobo_eligible,
    pobo_default_payer_entity_id,
    pobo_default_payer_entity_code,
    is_intercompany,
    linked_legal_entity_id,
    linked_legal_entity_code,
    netting_eligible,
    ic_settlement_method,
    kyc_status,
    risk_rating,
    status,
    corporate_id,
    ( SELECT count(*) AS count
           FROM public.party_bank_accounts pba
          WHERE ((pba.party_id = p.id) AND ((pba.status)::text = 'ACTIVE'::text))) AS active_bank_accounts,
    ( SELECT pba.iban
           FROM public.party_bank_accounts pba
          WHERE ((pba.party_id = p.id) AND (pba.is_primary = true))
         LIMIT 1) AS primary_iban
   FROM public.parties p
  WHERE (((roles)::text ~~ '%VENDOR%'::text) AND (pobo_eligible = true) AND ((status)::text = 'ACTIVE'::text) AND ((kyc_status)::text = ANY ((ARRAY['VERIFIED'::character varying, 'EXEMPTED'::character varying])::text[])));


ALTER VIEW public.vw_pobo_eligible_vendors OWNER TO postgres;

--
-- TOC entry 7183 (class 0 OID 0)
-- Dependencies: 299
-- Name: VIEW vw_pobo_eligible_vendors; Type: COMMENT; Schema: public; Owner: postgres
--

COMMENT ON VIEW public.vw_pobo_eligible_vendors IS 'POBO-eligible vendors with full context for payment factory operations.';


--
-- TOC entry 6496 (class 2606 OID 50566)
-- Name: account_attachments account_attachments_pkey; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.account_attachments
    ADD CONSTRAINT account_attachments_pkey PRIMARY KEY (id);


--
-- TOC entry 5963 (class 2606 OID 30667)
-- Name: beneficiaries beneficiaries_pkey; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.beneficiaries
    ADD CONSTRAINT beneficiaries_pkey PRIMARY KEY (id);


--
-- TOC entry 6405 (class 2606 OID 33947)
-- Name: calculated_charges calculated_charges_pkey; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.calculated_charges
    ADD CONSTRAINT calculated_charges_pkey PRIMARY KEY (id);


--
-- TOC entry 6398 (class 2606 OID 33917)
-- Name: calculated_taxes calculated_taxes_pkey; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.calculated_taxes
    ADD CONSTRAINT calculated_taxes_pkey PRIMARY KEY (id);


--
-- TOC entry 6389 (class 2606 OID 33888)
-- Name: charge_configurations charge_configurations_pkey; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.charge_configurations
    ADD CONSTRAINT charge_configurations_pkey PRIMARY KEY (id);


--
-- TOC entry 5965 (class 2606 OID 30679)
-- Name: corporates corporates_pkey; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.corporates
    ADD CONSTRAINT corporates_pkey PRIMARY KEY (id);


--
-- TOC entry 6506 (class 2606 OID 50693)
-- Name: credit_agreements credit_agreements_external_reference_key; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.credit_agreements
    ADD CONSTRAINT credit_agreements_external_reference_key UNIQUE (external_reference);


--
-- TOC entry 6508 (class 2606 OID 50691)
-- Name: credit_agreements credit_agreements_pkey; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.credit_agreements
    ADD CONSTRAINT credit_agreements_pkey PRIMARY KEY (id);


--
-- TOC entry 6517 (class 2606 OID 50723)
-- Name: credit_facilities credit_facilities_external_reference_key; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.credit_facilities
    ADD CONSTRAINT credit_facilities_external_reference_key UNIQUE (external_reference);


--
-- TOC entry 6519 (class 2606 OID 50721)
-- Name: credit_facilities credit_facilities_pkey; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.credit_facilities
    ADD CONSTRAINT credit_facilities_pkey PRIMARY KEY (id);


--
-- TOC entry 6529 (class 2606 OID 50760)
-- Name: credit_limits credit_limits_pkey; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.credit_limits
    ADD CONSTRAINT credit_limits_pkey PRIMARY KEY (id);


--
-- TOC entry 6454 (class 2606 OID 34194)
-- Name: escrow_contracts escrow_contracts_escrow_reference_key; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.escrow_contracts
    ADD CONSTRAINT escrow_contracts_escrow_reference_key UNIQUE (escrow_reference);


--
-- TOC entry 6456 (class 2606 OID 34192)
-- Name: escrow_contracts escrow_contracts_pkey; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.escrow_contracts
    ADD CONSTRAINT escrow_contracts_pkey PRIMARY KEY (id);


--
-- TOC entry 6440 (class 2606 OID 34134)
-- Name: exception_transactions exception_transactions_exception_number_key; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.exception_transactions
    ADD CONSTRAINT exception_transactions_exception_number_key UNIQUE (exception_number);


--
-- TOC entry 6442 (class 2606 OID 34132)
-- Name: exception_transactions exception_transactions_pkey; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.exception_transactions
    ADD CONSTRAINT exception_transactions_pkey PRIMARY KEY (id);


--
-- TOC entry 6546 (class 2606 OID 51031)
-- Name: fx_rates fx_rates_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.fx_rates
    ADD CONSTRAINT fx_rates_pkey PRIMARY KEY (id);


--
-- TOC entry 6548 (class 2606 OID 51033)
-- Name: fx_rates fx_rates_unique_rate; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.fx_rates
    ADD CONSTRAINT fx_rates_unique_rate UNIQUE (from_currency, to_currency, rate_date, rate_type, source);


--
-- TOC entry 6226 (class 2606 OID 33060)
-- Name: hierarchy_level_configs hierarchy_level_configs_pkey; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.hierarchy_level_configs
    ADD CONSTRAINT hierarchy_level_configs_pkey PRIMARY KEY (id);


--
-- TOC entry 6246 (class 2606 OID 33136)
-- Name: hierarchy_node_balance_history hierarchy_node_balance_history_pkey; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.hierarchy_node_balance_history
    ADD CONSTRAINT hierarchy_node_balance_history_pkey PRIMARY KEY (id);


--
-- TOC entry 6231 (class 2606 OID 33098)
-- Name: hierarchy_nodes hierarchy_nodes_pkey; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.hierarchy_nodes
    ADD CONSTRAINT hierarchy_nodes_pkey PRIMARY KEY (id);


--
-- TOC entry 6124 (class 2606 OID 31033)
-- Name: ihb_accounts ihb_accounts_account_reference_key; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.ihb_accounts
    ADD CONSTRAINT ihb_accounts_account_reference_key UNIQUE (account_reference);


--
-- TOC entry 6126 (class 2606 OID 31031)
-- Name: ihb_accounts ihb_accounts_pkey; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.ihb_accounts
    ADD CONSTRAINT ihb_accounts_pkey PRIMARY KEY (id);


--
-- TOC entry 6112 (class 2606 OID 30988)
-- Name: ihb_configuration ihb_configuration_config_key_key; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.ihb_configuration
    ADD CONSTRAINT ihb_configuration_config_key_key UNIQUE (config_key);


--
-- TOC entry 6114 (class 2606 OID 30986)
-- Name: ihb_configuration ihb_configuration_pkey; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.ihb_configuration
    ADD CONSTRAINT ihb_configuration_pkey PRIMARY KEY (id);


--
-- TOC entry 6158 (class 2606 OID 31104)
-- Name: ihb_deposits ihb_deposits_deposit_reference_key; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.ihb_deposits
    ADD CONSTRAINT ihb_deposits_deposit_reference_key UNIQUE (deposit_reference);


--
-- TOC entry 6160 (class 2606 OID 31102)
-- Name: ihb_deposits ihb_deposits_pkey; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.ihb_deposits
    ADD CONSTRAINT ihb_deposits_pkey PRIMARY KEY (id);


--
-- TOC entry 6117 (class 2606 OID 31010)
-- Name: ihb_entities ihb_entities_entity_code_key; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.ihb_entities
    ADD CONSTRAINT ihb_entities_entity_code_key UNIQUE (entity_code);


--
-- TOC entry 6119 (class 2606 OID 31008)
-- Name: ihb_entities ihb_entities_pkey; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.ihb_entities
    ADD CONSTRAINT ihb_entities_pkey PRIMARY KEY (id);


--
-- TOC entry 6200 (class 2606 OID 32778)
-- Name: ihb_interest_accruals ihb_interest_accruals_pkey; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.ihb_interest_accruals
    ADD CONSTRAINT ihb_interest_accruals_pkey PRIMARY KEY (id);


--
-- TOC entry 6137 (class 2606 OID 31067)
-- Name: ihb_loans ihb_loans_loan_reference_key; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.ihb_loans
    ADD CONSTRAINT ihb_loans_loan_reference_key UNIQUE (loan_reference);


--
-- TOC entry 6139 (class 2606 OID 31065)
-- Name: ihb_loans ihb_loans_pkey; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.ihb_loans
    ADD CONSTRAINT ihb_loans_pkey PRIMARY KEY (id);


--
-- TOC entry 6166 (class 2606 OID 31130)
-- Name: ihb_transactions ihb_transactions_pkey; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.ihb_transactions
    ADD CONSTRAINT ihb_transactions_pkey PRIMARY KEY (id);


--
-- TOC entry 6168 (class 2606 OID 31132)
-- Name: ihb_transactions ihb_transactions_transaction_reference_key; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.ihb_transactions
    ADD CONSTRAINT ihb_transactions_transaction_reference_key UNIQUE (transaction_reference);


--
-- TOC entry 6185 (class 2606 OID 31181)
-- Name: integration_connections integration_connections_pkey; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.integration_connections
    ADD CONSTRAINT integration_connections_pkey PRIMARY KEY (id);


--
-- TOC entry 6179 (class 2606 OID 32952)
-- Name: integration_connectors integration_connectors_connector_code_key; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.integration_connectors
    ADD CONSTRAINT integration_connectors_connector_code_key UNIQUE (connector_code);


--
-- TOC entry 6181 (class 2606 OID 31162)
-- Name: integration_connectors integration_connectors_pkey; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.integration_connectors
    ADD CONSTRAINT integration_connectors_pkey PRIMARY KEY (id);


--
-- TOC entry 6189 (class 2606 OID 31205)
-- Name: integration_data_flows integration_data_flows_pkey; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.integration_data_flows
    ADD CONSTRAINT integration_data_flows_pkey PRIMARY KEY (id);


--
-- TOC entry 6192 (class 2606 OID 31228)
-- Name: integration_field_mappings integration_field_mappings_pkey; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.integration_field_mappings
    ADD CONSTRAINT integration_field_mappings_pkey PRIMARY KEY (id);


--
-- TOC entry 6198 (class 2606 OID 31249)
-- Name: integration_sync_logs integration_sync_logs_pkey; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.integration_sync_logs
    ADD CONSTRAINT integration_sync_logs_pkey PRIMARY KEY (id);


--
-- TOC entry 6417 (class 2606 OID 33985)
-- Name: intercompany_recharges intercompany_recharges_pkey; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.intercompany_recharges
    ADD CONSTRAINT intercompany_recharges_pkey PRIMARY KEY (id);


--
-- TOC entry 6436 (class 2606 OID 34076)
-- Name: intercompany_transactions intercompany_transactions_pkey; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.intercompany_transactions
    ADD CONSTRAINT intercompany_transactions_pkey PRIMARY KEY (id);


--
-- TOC entry 6544 (class 2606 OID 50796)
-- Name: interest_configurations interest_configurations_pkey; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.interest_configurations
    ADD CONSTRAINT interest_configurations_pkey PRIMARY KEY (id);


--
-- TOC entry 6488 (class 2606 OID 50530)
-- Name: legal_entities legal_entities_entity_code_key; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.legal_entities
    ADD CONSTRAINT legal_entities_entity_code_key UNIQUE (entity_code);


--
-- TOC entry 6490 (class 2606 OID 50528)
-- Name: legal_entities legal_entities_pkey; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.legal_entities
    ADD CONSTRAINT legal_entities_pkey PRIMARY KEY (id);


--
-- TOC entry 6099 (class 2606 OID 30922)
-- Name: netting_cycles netting_cycles_cycle_reference_key; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.netting_cycles
    ADD CONSTRAINT netting_cycles_cycle_reference_key UNIQUE (cycle_reference);


--
-- TOC entry 6101 (class 2606 OID 30920)
-- Name: netting_cycles netting_cycles_pkey; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.netting_cycles
    ADD CONSTRAINT netting_cycles_pkey PRIMARY KEY (id);


--
-- TOC entry 6106 (class 2606 OID 30943)
-- Name: netting_entries netting_entries_pkey; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.netting_entries
    ADD CONSTRAINT netting_entries_pkey PRIMARY KEY (id);


--
-- TOC entry 6110 (class 2606 OID 30967)
-- Name: netting_settlements netting_settlements_pkey; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.netting_settlements
    ADD CONSTRAINT netting_settlements_pkey PRIMARY KEY (id);


--
-- TOC entry 6365 (class 2606 OID 33767)
-- Name: notification_preferences notification_preferences_pkey; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.notification_preferences
    ADD CONSTRAINT notification_preferences_pkey PRIMARY KEY (id);


--
-- TOC entry 6363 (class 2606 OID 33740)
-- Name: notifications notifications_pkey; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.notifications
    ADD CONSTRAINT notifications_pkey PRIMARY KEY (id);


--
-- TOC entry 6083 (class 2606 OID 30846)
-- Name: notional_pools notional_pools_pkey; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.notional_pools
    ADD CONSTRAINT notional_pools_pkey PRIMARY KEY (id);


--
-- TOC entry 6085 (class 2606 OID 30848)
-- Name: notional_pools notional_pools_pool_reference_key; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.notional_pools
    ADD CONSTRAINT notional_pools_pool_reference_key UNIQUE (pool_reference);


--
-- TOC entry 6213 (class 2606 OID 32806)
-- Name: parties parties_pkey; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.parties
    ADD CONSTRAINT parties_pkey PRIMARY KEY (id);


--
-- TOC entry 6219 (class 2606 OID 32820)
-- Name: party_bank_accounts party_bank_accounts_pkey; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.party_bank_accounts
    ADD CONSTRAINT party_bank_accounts_pkey PRIMARY KEY (id);


--
-- TOC entry 6224 (class 2606 OID 32836)
-- Name: party_documents party_documents_pkey; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.party_documents
    ADD CONSTRAINT party_documents_pkey PRIMARY KEY (id);


--
-- TOC entry 6572 (class 2606 OID 51570)
-- Name: payables payables_payable_number_key; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.payables
    ADD CONSTRAINT payables_payable_number_key UNIQUE (payable_number);


--
-- TOC entry 6574 (class 2606 OID 51568)
-- Name: payables payables_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.payables
    ADD CONSTRAINT payables_pkey PRIMARY KEY (id);


--
-- TOC entry 6347 (class 2606 OID 33679)
-- Name: payment_batches payment_batches_pkey; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.payment_batches
    ADD CONSTRAINT payment_batches_pkey PRIMARY KEY (id);


--
-- TOC entry 6355 (class 2606 OID 33707)
-- Name: payment_executions payment_executions_pkey; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.payment_executions
    ADD CONSTRAINT payment_executions_pkey PRIMARY KEY (id);


--
-- TOC entry 5983 (class 2606 OID 30693)
-- Name: physical_accounts physical_accounts_pkey; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.physical_accounts
    ADD CONSTRAINT physical_accounts_pkey PRIMARY KEY (id);


--
-- TOC entry 6424 (class 2606 OID 34024)
-- Name: pobo_authorizations pobo_authorizations_pkey; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.pobo_authorizations
    ADD CONSTRAINT pobo_authorizations_pkey PRIMARY KEY (id);


--
-- TOC entry 6095 (class 2606 OID 30890)
-- Name: pool_interest_calculations pool_interest_calculations_pkey; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.pool_interest_calculations
    ADD CONSTRAINT pool_interest_calculations_pkey PRIMARY KEY (id);


--
-- TOC entry 6090 (class 2606 OID 30867)
-- Name: pool_members pool_members_pkey; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.pool_members
    ADD CONSTRAINT pool_members_pkey PRIMARY KEY (id);


--
-- TOC entry 6467 (class 2606 OID 42301)
-- Name: program_charge_overrides program_charge_overrides_pkey; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.program_charge_overrides
    ADD CONSTRAINT program_charge_overrides_pkey PRIMARY KEY (id);


--
-- TOC entry 6331 (class 2606 OID 33528)
-- Name: receivable_line_items receivable_line_items_pkey; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.receivable_line_items
    ADD CONSTRAINT receivable_line_items_pkey PRIMARY KEY (id);


--
-- TOC entry 6328 (class 2606 OID 33490)
-- Name: receivable_payments receivable_payments_pkey; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.receivable_payments
    ADD CONSTRAINT receivable_payments_pkey PRIMARY KEY (id);


--
-- TOC entry 6317 (class 2606 OID 33427)
-- Name: receivables receivables_pkey; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.receivables
    ADD CONSTRAINT receivables_pkey PRIMARY KEY (id);


--
-- TOC entry 6372 (class 2606 OID 33788)
-- Name: scheduled_job_executions scheduled_job_executions_pkey; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.scheduled_job_executions
    ADD CONSTRAINT scheduled_job_executions_pkey PRIMARY KEY (id);


--
-- TOC entry 6077 (class 2606 OID 30817)
-- Name: sweep_executions sweep_executions_execution_reference_key; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.sweep_executions
    ADD CONSTRAINT sweep_executions_execution_reference_key UNIQUE (execution_reference);


--
-- TOC entry 6079 (class 2606 OID 30815)
-- Name: sweep_executions sweep_executions_pkey; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.sweep_executions
    ADD CONSTRAINT sweep_executions_pkey PRIMARY KEY (id);


--
-- TOC entry 6070 (class 2606 OID 30789)
-- Name: sweep_rule_sources sweep_rule_sources_pkey; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.sweep_rule_sources
    ADD CONSTRAINT sweep_rule_sources_pkey PRIMARY KEY (id);


--
-- TOC entry 6064 (class 2606 OID 30772)
-- Name: sweep_rules sweep_rules_pkey; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.sweep_rules
    ADD CONSTRAINT sweep_rules_pkey PRIMARY KEY (id);


--
-- TOC entry 6066 (class 2606 OID 30774)
-- Name: sweep_rules sweep_rules_rule_reference_key; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.sweep_rules
    ADD CONSTRAINT sweep_rules_rule_reference_key UNIQUE (rule_reference);


--
-- TOC entry 6385 (class 2606 OID 33851)
-- Name: tax_configurations tax_configurations_pkey; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.tax_configurations
    ADD CONSTRAINT tax_configurations_pkey PRIMARY KEY (id);


--
-- TOC entry 6376 (class 2606 OID 33821)
-- Name: tax_jurisdictions tax_jurisdictions_pkey; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.tax_jurisdictions
    ADD CONSTRAINT tax_jurisdictions_pkey PRIMARY KEY (id);


--
-- TOC entry 5992 (class 2606 OID 30745)
-- Name: unified_programs uk_4ulctvh57gctasa55e3mh08w2; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.unified_programs
    ADD CONSTRAINT uk_4ulctvh57gctasa55e3mh08w2 UNIQUE (program_code);


--
-- TOC entry 6438 (class 2606 OID 34084)
-- Name: intercompany_transactions uk_5022xgiufv37lx2sc8e45p0rq; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.intercompany_transactions
    ADD CONSTRAINT uk_5022xgiufv37lx2sc8e45p0rq UNIQUE (transaction_ref);


--
-- TOC entry 6170 (class 2606 OID 34219)
-- Name: ihb_transactions uk_8ol1yqqtymu4sxne7jljfsv3l; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.ihb_transactions
    ADD CONSTRAINT uk_8ol1yqqtymu4sxne7jljfsv3l UNIQUE (transaction_ref);


--
-- TOC entry 6215 (class 2606 OID 32842)
-- Name: parties uk_a5n1ru9ep6hn0g5cyl335udde; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.parties
    ADD CONSTRAINT uk_a5n1ru9ep6hn0g5cyl335udde UNIQUE (party_code);


--
-- TOC entry 6349 (class 2606 OID 33681)
-- Name: payment_batches uk_batch_reference; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.payment_batches
    ADD CONSTRAINT uk_batch_reference UNIQUE (batch_reference);


--
-- TOC entry 6015 (class 2606 OID 30747)
-- Name: va_movements uk_brstygra9cuuy22yuqvnrw7si; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.va_movements
    ADD CONSTRAINT uk_brstygra9cuuy22yuqvnrw7si UNIQUE (reference_number);


--
-- TOC entry 6396 (class 2606 OID 33890)
-- Name: charge_configurations uk_charge_code; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.charge_configurations
    ADD CONSTRAINT uk_charge_code UNIQUE (charge_code);


--
-- TOC entry 5985 (class 2606 OID 30743)
-- Name: physical_accounts uk_iu28i87uc2saa492plykwj1x8; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.physical_accounts
    ADD CONSTRAINT uk_iu28i87uc2saa492plykwj1x8 UNIQUE (account_number);


--
-- TOC entry 6378 (class 2606 OID 33823)
-- Name: tax_jurisdictions uk_jurisdiction_code; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.tax_jurisdictions
    ADD CONSTRAINT uk_jurisdiction_code UNIQUE (jurisdiction_code);


--
-- TOC entry 6367 (class 2606 OID 33769)
-- Name: notification_preferences uk_notif_pref; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.notification_preferences
    ADD CONSTRAINT uk_notif_pref UNIQUE (corporate_id, user_id, notification_type);


--
-- TOC entry 6426 (class 2606 OID 34026)
-- Name: pobo_authorizations uk_pobo_auth_code; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.pobo_authorizations
    ADD CONSTRAINT uk_pobo_auth_code UNIQUE (authorization_code);


--
-- TOC entry 6428 (class 2606 OID 34028)
-- Name: pobo_authorizations uk_pobo_auth_pair; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.pobo_authorizations
    ADD CONSTRAINT uk_pobo_auth_pair UNIQUE (payer_entity_id, behalf_entity_id, status);


--
-- TOC entry 6055 (class 2606 OID 30751)
-- Name: virtual_accounts uk_r5hfiln0u4kx8ncvvbql755xm; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.virtual_accounts
    ADD CONSTRAINT uk_r5hfiln0u4kx8ncvvbql755xm UNIQUE (viban);


--
-- TOC entry 5967 (class 2606 OID 30741)
-- Name: corporates uk_r73e355lqw4h1j2ebwswikc2f; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.corporates
    ADD CONSTRAINT uk_r73e355lqw4h1j2ebwswikc2f UNIQUE (corporate_id);


--
-- TOC entry 6319 (class 2606 OID 33429)
-- Name: receivables uk_receivable_number; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.receivables
    ADD CONSTRAINT uk_receivable_number UNIQUE (receivable_number);


--
-- TOC entry 6419 (class 2606 OID 33987)
-- Name: intercompany_recharges uk_recharge_reference; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.intercompany_recharges
    ADD CONSTRAINT uk_recharge_reference UNIQUE (recharge_reference);


--
-- TOC entry 6387 (class 2606 OID 33853)
-- Name: tax_configurations uk_tax_code; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.tax_configurations
    ADD CONSTRAINT uk_tax_code UNIQUE (tax_code);


--
-- TOC entry 6057 (class 2606 OID 50920)
-- Name: virtual_accounts uk_virtual_accounts_program_va_number; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.virtual_accounts
    ADD CONSTRAINT uk_virtual_accounts_program_va_number UNIQUE (program_id, va_number);


--
-- TOC entry 5994 (class 2606 OID 30709)
-- Name: unified_programs unified_programs_pkey; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.unified_programs
    ADD CONSTRAINT unified_programs_pkey PRIMARY KEY (id);


--
-- TOC entry 6342 (class 2606 OID 33551)
-- Name: unmatched_payments unmatched_payments_pkey; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.unmatched_payments
    ADD CONSTRAINT unmatched_payments_pkey PRIMARY KEY (id);


--
-- TOC entry 6515 (class 2606 OID 50954)
-- Name: credit_agreements uq_credit_agreement_ref; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.credit_agreements
    ADD CONSTRAINT uq_credit_agreement_ref UNIQUE (external_reference);


--
-- TOC entry 6527 (class 2606 OID 50956)
-- Name: credit_facilities uq_facility_ref; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.credit_facilities
    ADD CONSTRAINT uq_facility_ref UNIQUE (external_reference);


--
-- TOC entry 6492 (class 2606 OID 50884)
-- Name: legal_entities uq_legal_entity_bancs_customer; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.legal_entities
    ADD CONSTRAINT uq_legal_entity_bancs_customer UNIQUE (bancs_customer_id);


--
-- TOC entry 6494 (class 2606 OID 50960)
-- Name: legal_entities uq_legal_entity_code; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.legal_entities
    ADD CONSTRAINT uq_legal_entity_code UNIQUE (entity_code);


--
-- TOC entry 6254 (class 2606 OID 33187)
-- Name: viban_pools uq_pool_code; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.viban_pools
    ADD CONSTRAINT uq_pool_code UNIQUE (program_id, pool_code);


--
-- TOC entry 6469 (class 2606 OID 42303)
-- Name: program_charge_overrides uq_program_charge_active; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.program_charge_overrides
    ADD CONSTRAINT uq_program_charge_active UNIQUE (program_id, charge_code, status);


--
-- TOC entry 6229 (class 2606 OID 33062)
-- Name: hierarchy_level_configs uq_program_level; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.hierarchy_level_configs
    ADD CONSTRAINT uq_program_level UNIQUE (program_id, level_number);


--
-- TOC entry 6244 (class 2606 OID 33100)
-- Name: hierarchy_nodes uq_program_path; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.hierarchy_nodes
    ADD CONSTRAINT uq_program_path UNIQUE (program_id, materialized_path);


--
-- TOC entry 6288 (class 2606 OID 33314)
-- Name: viban_sequences uq_sequence_pool_prefix; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.viban_sequences
    ADD CONSTRAINT uq_sequence_pool_prefix UNIQUE (pool_id, prefix);


--
-- TOC entry 6504 (class 2606 OID 50568)
-- Name: account_attachments uq_va_entity_relationship; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.account_attachments
    ADD CONSTRAINT uq_va_entity_relationship UNIQUE (virtual_account_id, legal_entity_id, relationship_type);


--
-- TOC entry 6017 (class 2606 OID 30725)
-- Name: va_movements va_movements_pkey; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.va_movements
    ADD CONSTRAINT va_movements_pkey PRIMARY KEY (id);


--
-- TOC entry 6256 (class 2606 OID 33185)
-- Name: viban_pools viban_pools_pkey; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.viban_pools
    ADD CONSTRAINT viban_pools_pkey PRIMARY KEY (id);


--
-- TOC entry 6290 (class 2606 OID 33312)
-- Name: viban_sequences viban_sequences_pkey; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.viban_sequences
    ADD CONSTRAINT viban_sequences_pkey PRIMARY KEY (id);


--
-- TOC entry 6285 (class 2606 OID 33276)
-- Name: viban_usage_log viban_usage_log_pkey; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.viban_usage_log
    ADD CONSTRAINT viban_usage_log_pkey PRIMARY KEY (id);


--
-- TOC entry 6272 (class 2606 OID 33220)
-- Name: vibans vibans_pkey; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.vibans
    ADD CONSTRAINT vibans_pkey PRIMARY KEY (id);


--
-- TOC entry 6274 (class 2606 OID 33222)
-- Name: vibans vibans_viban_key; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.vibans
    ADD CONSTRAINT vibans_viban_key UNIQUE (viban);


--
-- TOC entry 6059 (class 2606 OID 30739)
-- Name: virtual_accounts virtual_accounts_pkey; Type: CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.virtual_accounts
    ADD CONSTRAINT virtual_accounts_pkey PRIMARY KEY (id);


--
-- TOC entry 6497 (class 1259 OID 50579)
-- Name: idx_aa_effective; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_aa_effective ON public.account_attachments USING btree (effective_from, effective_to);


--
-- TOC entry 6498 (class 1259 OID 50575)
-- Name: idx_aa_entity; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_aa_entity ON public.account_attachments USING btree (legal_entity_id);


--
-- TOC entry 6499 (class 1259 OID 50577)
-- Name: idx_aa_primary; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_aa_primary ON public.account_attachments USING btree (is_primary);


--
-- TOC entry 6500 (class 1259 OID 50576)
-- Name: idx_aa_relationship; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_aa_relationship ON public.account_attachments USING btree (relationship_type);


--
-- TOC entry 6501 (class 1259 OID 50578)
-- Name: idx_aa_status; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_aa_status ON public.account_attachments USING btree (status);


--
-- TOC entry 6502 (class 1259 OID 50574)
-- Name: idx_aa_va; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_aa_va ON public.account_attachments USING btree (virtual_account_id);


--
-- TOC entry 6343 (class 1259 OID 33687)
-- Name: idx_batch_corporate; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_batch_corporate ON public.payment_batches USING btree (corporate_id);


--
-- TOC entry 6344 (class 1259 OID 33689)
-- Name: idx_batch_scheduled; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_batch_scheduled ON public.payment_batches USING btree (scheduled_date);


--
-- TOC entry 6345 (class 1259 OID 33688)
-- Name: idx_batch_status; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_batch_status ON public.payment_batches USING btree (status);


--
-- TOC entry 6509 (class 1259 OID 50695)
-- Name: idx_ca_bank; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_ca_bank ON public.credit_agreements USING btree (bank_code);


--
-- TOC entry 6510 (class 1259 OID 50694)
-- Name: idx_ca_corporate; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_ca_corporate ON public.credit_agreements USING btree (corporate_id);


--
-- TOC entry 6511 (class 1259 OID 50697)
-- Name: idx_ca_expiry; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_ca_expiry ON public.credit_agreements USING btree (expiry_date);


--
-- TOC entry 6512 (class 1259 OID 50952)
-- Name: idx_ca_external_ref; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_ca_external_ref ON public.credit_agreements USING btree (external_reference);


--
-- TOC entry 6513 (class 1259 OID 50696)
-- Name: idx_ca_status; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_ca_status ON public.credit_agreements USING btree (status);


--
-- TOC entry 6406 (class 1259 OID 33954)
-- Name: idx_calc_charge_code; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_calc_charge_code ON public.calculated_charges USING btree (charge_code);


--
-- TOC entry 6407 (class 1259 OID 33953)
-- Name: idx_calc_charge_reference; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_calc_charge_reference ON public.calculated_charges USING btree (reference_type, reference_id);


--
-- TOC entry 6408 (class 1259 OID 33956)
-- Name: idx_calc_charge_status; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_calc_charge_status ON public.calculated_charges USING btree (status);


--
-- TOC entry 6409 (class 1259 OID 33955)
-- Name: idx_calc_charge_type; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_calc_charge_type ON public.calculated_charges USING btree (charge_type);


--
-- TOC entry 6410 (class 1259 OID 34171)
-- Name: idx_calc_charge_va_id; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_calc_charge_va_id ON public.calculated_charges USING btree (va_id) WHERE (va_id IS NOT NULL);


--
-- TOC entry 6399 (class 1259 OID 33924)
-- Name: idx_calc_tax_code; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_calc_tax_code ON public.calculated_taxes USING btree (tax_code);


--
-- TOC entry 6400 (class 1259 OID 33923)
-- Name: idx_calc_tax_reference; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_calc_tax_reference ON public.calculated_taxes USING btree (reference_type, reference_id);


--
-- TOC entry 6401 (class 1259 OID 33926)
-- Name: idx_calc_tax_status; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_calc_tax_status ON public.calculated_taxes USING btree (status);


--
-- TOC entry 6402 (class 1259 OID 33925)
-- Name: idx_calc_tax_type; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_calc_tax_type ON public.calculated_taxes USING btree (tax_type);


--
-- TOC entry 6403 (class 1259 OID 34172)
-- Name: idx_calc_tax_va_id; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_calc_tax_va_id ON public.calculated_taxes USING btree (va_id) WHERE (va_id IS NOT NULL);


--
-- TOC entry 6520 (class 1259 OID 50729)
-- Name: idx_cf_agreement; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_cf_agreement ON public.credit_facilities USING btree (credit_agreement_id);


--
-- TOC entry 6521 (class 1259 OID 50730)
-- Name: idx_cf_corporate; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_cf_corporate ON public.credit_facilities USING btree (corporate_id);


--
-- TOC entry 6522 (class 1259 OID 50734)
-- Name: idx_cf_expiry; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_cf_expiry ON public.credit_facilities USING btree (expiry_date);


--
-- TOC entry 6523 (class 1259 OID 50733)
-- Name: idx_cf_physical; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_cf_physical ON public.credit_facilities USING btree (physical_account_id);


--
-- TOC entry 6524 (class 1259 OID 50732)
-- Name: idx_cf_status; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_cf_status ON public.credit_facilities USING btree (status);


--
-- TOC entry 6525 (class 1259 OID 50731)
-- Name: idx_cf_type; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_cf_type ON public.credit_facilities USING btree (facility_type);


--
-- TOC entry 6390 (class 1259 OID 33892)
-- Name: idx_charge_config_category; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_charge_config_category ON public.charge_configurations USING btree (charge_category);


--
-- TOC entry 6391 (class 1259 OID 33893)
-- Name: idx_charge_config_method; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_charge_config_method ON public.charge_configurations USING btree (applies_to_payment_method);


--
-- TOC entry 6392 (class 1259 OID 33894)
-- Name: idx_charge_config_priority; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_charge_config_priority ON public.charge_configurations USING btree (applies_to_priority);


--
-- TOC entry 6393 (class 1259 OID 33895)
-- Name: idx_charge_config_status; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_charge_config_status ON public.charge_configurations USING btree (status);


--
-- TOC entry 6394 (class 1259 OID 33891)
-- Name: idx_charge_config_type; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_charge_config_type ON public.charge_configurations USING btree (charge_type);


--
-- TOC entry 6530 (class 1259 OID 50766)
-- Name: idx_cl_corporate; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_cl_corporate ON public.credit_limits USING btree (corporate_id);


--
-- TOC entry 6531 (class 1259 OID 50772)
-- Name: idx_cl_currency; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_cl_currency ON public.credit_limits USING btree (limit_currency);


--
-- TOC entry 6532 (class 1259 OID 50767)
-- Name: idx_cl_facility; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_cl_facility ON public.credit_limits USING btree (credit_facility_id);


--
-- TOC entry 6533 (class 1259 OID 50770)
-- Name: idx_cl_limit_type; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_cl_limit_type ON public.credit_limits USING btree (limit_type);


--
-- TOC entry 6534 (class 1259 OID 50882)
-- Name: idx_cl_parent_limit; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_cl_parent_limit ON public.credit_limits USING btree (parent_limit_id) WHERE (parent_limit_id IS NOT NULL);


--
-- TOC entry 6535 (class 1259 OID 50771)
-- Name: idx_cl_status; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_cl_status ON public.credit_limits USING btree (status);


--
-- TOC entry 6536 (class 1259 OID 50768)
-- Name: idx_cl_target; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_cl_target ON public.credit_limits USING btree (target_id);


--
-- TOC entry 6537 (class 1259 OID 50769)
-- Name: idx_cl_target_type; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_cl_target_type ON public.credit_limits USING btree (target_type);


--
-- TOC entry 6171 (class 1259 OID 32960)
-- Name: idx_connectors_category_status; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_connectors_category_status ON public.integration_connectors USING btree (category, status);


--
-- TOC entry 6172 (class 1259 OID 32961)
-- Name: idx_connectors_region; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_connectors_region ON public.integration_connectors USING btree (region);


--
-- TOC entry 6173 (class 1259 OID 32962)
-- Name: idx_connectors_sort_order; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_connectors_sort_order ON public.integration_connectors USING btree (sort_order);


--
-- TOC entry 6457 (class 1259 OID 34196)
-- Name: idx_escrow_buyer; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_escrow_buyer ON public.escrow_contracts USING btree (buyer_id);


--
-- TOC entry 6458 (class 1259 OID 34195)
-- Name: idx_escrow_reference; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_escrow_reference ON public.escrow_contracts USING btree (escrow_reference);


--
-- TOC entry 6459 (class 1259 OID 34197)
-- Name: idx_escrow_seller; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_escrow_seller ON public.escrow_contracts USING btree (seller_id);


--
-- TOC entry 6460 (class 1259 OID 34198)
-- Name: idx_escrow_status; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_escrow_status ON public.escrow_contracts USING btree (status);


--
-- TOC entry 6461 (class 1259 OID 34199)
-- Name: idx_escrow_va; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_escrow_va ON public.escrow_contracts USING btree (escrow_va_id);


--
-- TOC entry 6443 (class 1259 OID 34204)
-- Name: idx_exception_txn_correlation; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_exception_txn_correlation ON public.exception_transactions USING btree (correlation_id);


--
-- TOC entry 6444 (class 1259 OID 34158)
-- Name: idx_exception_txn_created; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_exception_txn_created ON public.exception_transactions USING btree (created_at DESC);


--
-- TOC entry 6445 (class 1259 OID 50957)
-- Name: idx_exception_txn_exception_va; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_exception_txn_exception_va ON public.exception_transactions USING btree (exception_va_id);


--
-- TOC entry 6446 (class 1259 OID 34157)
-- Name: idx_exception_txn_original; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_exception_txn_original ON public.exception_transactions USING btree (original_transaction_id);


--
-- TOC entry 6447 (class 1259 OID 34203)
-- Name: idx_exception_txn_priority; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_exception_txn_priority ON public.exception_transactions USING btree (priority);


--
-- TOC entry 6448 (class 1259 OID 34201)
-- Name: idx_exception_txn_program; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_exception_txn_program ON public.exception_transactions USING btree (program_id);


--
-- TOC entry 6449 (class 1259 OID 34155)
-- Name: idx_exception_txn_status; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_exception_txn_status ON public.exception_transactions USING btree (status);


--
-- TOC entry 6450 (class 1259 OID 50958)
-- Name: idx_exception_txn_type; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_exception_txn_type ON public.exception_transactions USING btree (exception_type);


--
-- TOC entry 6451 (class 1259 OID 34156)
-- Name: idx_exception_txn_va; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_exception_txn_va ON public.exception_transactions USING btree (exception_va_id);


--
-- TOC entry 6452 (class 1259 OID 34202)
-- Name: idx_exception_txn_value_date; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_exception_txn_value_date ON public.exception_transactions USING btree (value_date);


--
-- TOC entry 6350 (class 1259 OID 33719)
-- Name: idx_exec_batch; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_exec_batch ON public.payment_executions USING btree (batch_id);


--
-- TOC entry 6351 (class 1259 OID 33721)
-- Name: idx_exec_date; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_exec_date ON public.payment_executions USING btree (execution_date);


--
-- TOC entry 6352 (class 1259 OID 33718)
-- Name: idx_exec_payable; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_exec_payable ON public.payment_executions USING btree (payable_id);


--
-- TOC entry 6353 (class 1259 OID 33720)
-- Name: idx_exec_status; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_exec_status ON public.payment_executions USING btree (status);


--
-- TOC entry 6549 (class 1259 OID 51045)
-- Name: idx_fx_rates_active; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_fx_rates_active ON public.fx_rates USING btree (from_currency, to_currency, is_active) WHERE (is_active = true);


--
-- TOC entry 6550 (class 1259 OID 51063)
-- Name: idx_fx_rates_approved_by; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_fx_rates_approved_by ON public.fx_rates USING btree (approved_by) WHERE (approved_by IS NOT NULL);


--
-- TOC entry 6551 (class 1259 OID 51047)
-- Name: idx_fx_rates_corporate; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_fx_rates_corporate ON public.fx_rates USING btree (corporate_id, from_currency, to_currency) WHERE (corporate_id IS NOT NULL);


--
-- TOC entry 6552 (class 1259 OID 51044)
-- Name: idx_fx_rates_currency_pair; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_fx_rates_currency_pair ON public.fx_rates USING btree (from_currency, to_currency, rate_date DESC);


--
-- TOC entry 6553 (class 1259 OID 51048)
-- Name: idx_fx_rates_date_range; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_fx_rates_date_range ON public.fx_rates USING btree (rate_date, effective_from, effective_to);


--
-- TOC entry 6554 (class 1259 OID 51046)
-- Name: idx_fx_rates_source; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_fx_rates_source ON public.fx_rates USING btree (source, rate_date DESC);


--
-- TOC entry 6232 (class 1259 OID 50907)
-- Name: idx_hierarchy_nodes_corporate; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_hierarchy_nodes_corporate ON public.hierarchy_nodes USING btree (corporate_id);


--
-- TOC entry 6233 (class 1259 OID 33121)
-- Name: idx_hierarchy_nodes_leaf; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_hierarchy_nodes_leaf ON public.hierarchy_nodes USING btree (program_id, is_leaf) WHERE (is_leaf = true);


--
-- TOC entry 6234 (class 1259 OID 50906)
-- Name: idx_hierarchy_nodes_legal_entity; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_hierarchy_nodes_legal_entity ON public.hierarchy_nodes USING btree (legal_entity_id);


--
-- TOC entry 6235 (class 1259 OID 33119)
-- Name: idx_hierarchy_nodes_level; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_hierarchy_nodes_level ON public.hierarchy_nodes USING btree (program_id, level_number);


--
-- TOC entry 6236 (class 1259 OID 33117)
-- Name: idx_hierarchy_nodes_parent_id; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_hierarchy_nodes_parent_id ON public.hierarchy_nodes USING btree (parent_id);


--
-- TOC entry 6237 (class 1259 OID 33123)
-- Name: idx_hierarchy_nodes_path_btree; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_hierarchy_nodes_path_btree ON public.hierarchy_nodes USING btree (materialized_path varchar_pattern_ops);


--
-- TOC entry 6238 (class 1259 OID 33122)
-- Name: idx_hierarchy_nodes_path_gist; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_hierarchy_nodes_path_gist ON public.hierarchy_nodes USING gist (materialized_path public.gist_trgm_ops);


--
-- TOC entry 6239 (class 1259 OID 33116)
-- Name: idx_hierarchy_nodes_program_id; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_hierarchy_nodes_program_id ON public.hierarchy_nodes USING btree (program_id);


--
-- TOC entry 6240 (class 1259 OID 33120)
-- Name: idx_hierarchy_nodes_status; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_hierarchy_nodes_status ON public.hierarchy_nodes USING btree (status);


--
-- TOC entry 6241 (class 1259 OID 33124)
-- Name: idx_hierarchy_nodes_tree; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_hierarchy_nodes_tree ON public.hierarchy_nodes USING btree (program_id, parent_id, display_order);


--
-- TOC entry 6242 (class 1259 OID 33118)
-- Name: idx_hierarchy_nodes_virtual_account_id; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_hierarchy_nodes_virtual_account_id ON public.hierarchy_nodes USING btree (virtual_account_id) WHERE (virtual_account_id IS NOT NULL);


--
-- TOC entry 6538 (class 1259 OID 50797)
-- Name: idx_ic_corporate; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_ic_corporate ON public.interest_configurations USING btree (corporate_id);


--
-- TOC entry 6539 (class 1259 OID 50800)
-- Name: idx_ic_currency; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_ic_currency ON public.interest_configurations USING btree (currency_code);


--
-- TOC entry 6540 (class 1259 OID 50801)
-- Name: idx_ic_status; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_ic_status ON public.interest_configurations USING btree (status);


--
-- TOC entry 6541 (class 1259 OID 50798)
-- Name: idx_ic_target; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_ic_target ON public.interest_configurations USING btree (target_id);


--
-- TOC entry 6429 (class 1259 OID 34079)
-- Name: idx_ic_tx_behalf_entity; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_ic_tx_behalf_entity ON public.intercompany_transactions USING btree (behalf_entity_id);


--
-- TOC entry 6430 (class 1259 OID 34078)
-- Name: idx_ic_tx_paying_entity; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_ic_tx_paying_entity ON public.intercompany_transactions USING btree (paying_entity_id);


--
-- TOC entry 6431 (class 1259 OID 34077)
-- Name: idx_ic_tx_ref; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_ic_tx_ref ON public.intercompany_transactions USING btree (transaction_ref);


--
-- TOC entry 6432 (class 1259 OID 34081)
-- Name: idx_ic_tx_settlement; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_ic_tx_settlement ON public.intercompany_transactions USING btree (settlement_ref);


--
-- TOC entry 6433 (class 1259 OID 34080)
-- Name: idx_ic_tx_status; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_ic_tx_status ON public.intercompany_transactions USING btree (status);


--
-- TOC entry 6434 (class 1259 OID 34082)
-- Name: idx_ic_tx_type; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_ic_tx_type ON public.intercompany_transactions USING btree (transaction_type);


--
-- TOC entry 6542 (class 1259 OID 50799)
-- Name: idx_ic_type; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_ic_type ON public.interest_configurations USING btree (config_type);


--
-- TOC entry 6120 (class 1259 OID 31039)
-- Name: idx_ihb_accounts_entity; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_ihb_accounts_entity ON public.ihb_accounts USING btree (entity_id);


--
-- TOC entry 6121 (class 1259 OID 31041)
-- Name: idx_ihb_accounts_status; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_ihb_accounts_status ON public.ihb_accounts USING btree (status);


--
-- TOC entry 6122 (class 1259 OID 31040)
-- Name: idx_ihb_accounts_type; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_ihb_accounts_type ON public.ihb_accounts USING btree (account_type);


--
-- TOC entry 6140 (class 1259 OID 51156)
-- Name: idx_ihb_deposit_auto_created; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_ihb_deposit_auto_created ON public.ihb_deposits USING btree (auto_created) WHERE (auto_created = true);


--
-- TOC entry 6141 (class 1259 OID 51170)
-- Name: idx_ihb_deposit_corporate; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_ihb_deposit_corporate ON public.ihb_deposits USING btree (corporate_id);


--
-- TOC entry 6142 (class 1259 OID 51171)
-- Name: idx_ihb_deposit_depositor; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_ihb_deposit_depositor ON public.ihb_deposits USING btree (depositor_legal_entity_id);


--
-- TOC entry 6143 (class 1259 OID 51168)
-- Name: idx_ihb_deposit_depositor_entity; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_ihb_deposit_depositor_entity ON public.ihb_deposits USING btree (depositor_legal_entity_id);


--
-- TOC entry 6144 (class 1259 OID 51173)
-- Name: idx_ihb_deposit_status; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_ihb_deposit_status ON public.ihb_deposits USING btree (status);


--
-- TOC entry 6145 (class 1259 OID 51174)
-- Name: idx_ihb_deposit_sweep; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_ihb_deposit_sweep ON public.ihb_deposits USING btree (sweep_execution_reference);


--
-- TOC entry 6146 (class 1259 OID 51155)
-- Name: idx_ihb_deposit_sweep_ref; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_ihb_deposit_sweep_ref ON public.ihb_deposits USING btree (sweep_execution_reference);


--
-- TOC entry 6147 (class 1259 OID 51157)
-- Name: idx_ihb_deposit_sweep_rule; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_ihb_deposit_sweep_rule ON public.ihb_deposits USING btree (sweep_rule_id);


--
-- TOC entry 6148 (class 1259 OID 51172)
-- Name: idx_ihb_deposit_treasury; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_ihb_deposit_treasury ON public.ihb_deposits USING btree (treasury_legal_entity_id);


--
-- TOC entry 6149 (class 1259 OID 51169)
-- Name: idx_ihb_deposit_treasury_entity; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_ihb_deposit_treasury_entity ON public.ihb_deposits USING btree (treasury_legal_entity_id);


--
-- TOC entry 6150 (class 1259 OID 51116)
-- Name: idx_ihb_deposits_corporate; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_ihb_deposits_corporate ON public.ihb_deposits USING btree (corporate_id);


--
-- TOC entry 6151 (class 1259 OID 51114)
-- Name: idx_ihb_deposits_depositor_le; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_ihb_deposits_depositor_le ON public.ihb_deposits USING btree (depositor_legal_entity_id);


--
-- TOC entry 6152 (class 1259 OID 51117)
-- Name: idx_ihb_deposits_depositor_va; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_ihb_deposits_depositor_va ON public.ihb_deposits USING btree (depositor_va_id);


--
-- TOC entry 6153 (class 1259 OID 31110)
-- Name: idx_ihb_deposits_entity; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_ihb_deposits_entity ON public.ihb_deposits USING btree (depositor_entity_id);


--
-- TOC entry 6154 (class 1259 OID 31112)
-- Name: idx_ihb_deposits_maturity; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_ihb_deposits_maturity ON public.ihb_deposits USING btree (maturity_date);


--
-- TOC entry 6155 (class 1259 OID 31111)
-- Name: idx_ihb_deposits_status; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_ihb_deposits_status ON public.ihb_deposits USING btree (status);


--
-- TOC entry 6156 (class 1259 OID 51115)
-- Name: idx_ihb_deposits_treasury_le; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_ihb_deposits_treasury_le ON public.ihb_deposits USING btree (treasury_legal_entity_id);


--
-- TOC entry 6115 (class 1259 OID 31011)
-- Name: idx_ihb_entities_status; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_ihb_entities_status ON public.ihb_entities USING btree (status);


--
-- TOC entry 6127 (class 1259 OID 31079)
-- Name: idx_ihb_loans_borrower; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_ihb_loans_borrower ON public.ihb_loans USING btree (borrower_entity_id);


--
-- TOC entry 6128 (class 1259 OID 51110)
-- Name: idx_ihb_loans_borrower_le; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_ihb_loans_borrower_le ON public.ihb_loans USING btree (borrower_legal_entity_id);


--
-- TOC entry 6129 (class 1259 OID 51113)
-- Name: idx_ihb_loans_borrower_va; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_ihb_loans_borrower_va ON public.ihb_loans USING btree (borrower_va_id);


--
-- TOC entry 6130 (class 1259 OID 51111)
-- Name: idx_ihb_loans_corporate; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_ihb_loans_corporate ON public.ihb_loans USING btree (corporate_id);


--
-- TOC entry 6131 (class 1259 OID 31078)
-- Name: idx_ihb_loans_lender; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_ihb_loans_lender ON public.ihb_loans USING btree (lender_entity_id);


--
-- TOC entry 6132 (class 1259 OID 51109)
-- Name: idx_ihb_loans_lender_le; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_ihb_loans_lender_le ON public.ihb_loans USING btree (lender_legal_entity_id);


--
-- TOC entry 6133 (class 1259 OID 51112)
-- Name: idx_ihb_loans_lender_va; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_ihb_loans_lender_va ON public.ihb_loans USING btree (lender_va_id);


--
-- TOC entry 6134 (class 1259 OID 31081)
-- Name: idx_ihb_loans_maturity; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_ihb_loans_maturity ON public.ihb_loans USING btree (maturity_date);


--
-- TOC entry 6135 (class 1259 OID 31080)
-- Name: idx_ihb_loans_status; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_ihb_loans_status ON public.ihb_loans USING btree (status);


--
-- TOC entry 6161 (class 1259 OID 31146)
-- Name: idx_ihb_txn_credit; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_ihb_txn_credit ON public.ihb_transactions USING btree (credit_entity_id);


--
-- TOC entry 6162 (class 1259 OID 31144)
-- Name: idx_ihb_txn_date; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_ihb_txn_date ON public.ihb_transactions USING btree (value_date);


--
-- TOC entry 6163 (class 1259 OID 31145)
-- Name: idx_ihb_txn_debit; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_ihb_txn_debit ON public.ihb_transactions USING btree (debit_entity_id);


--
-- TOC entry 6164 (class 1259 OID 31143)
-- Name: idx_ihb_txn_type; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_ihb_txn_type ON public.ihb_transactions USING btree (transaction_type);


--
-- TOC entry 6182 (class 1259 OID 31187)
-- Name: idx_int_conn_connector; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_int_conn_connector ON public.integration_connections USING btree (connector_id);


--
-- TOC entry 6183 (class 1259 OID 31188)
-- Name: idx_int_conn_status; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_int_conn_status ON public.integration_connections USING btree (status);


--
-- TOC entry 6186 (class 1259 OID 31211)
-- Name: idx_int_flow_conn; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_int_flow_conn ON public.integration_data_flows USING btree (connection_id);


--
-- TOC entry 6187 (class 1259 OID 31212)
-- Name: idx_int_flow_status; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_int_flow_status ON public.integration_data_flows USING btree (status);


--
-- TOC entry 6190 (class 1259 OID 31234)
-- Name: idx_int_mapping_flow; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_int_mapping_flow ON public.integration_field_mappings USING btree (flow_id);


--
-- TOC entry 6193 (class 1259 OID 31260)
-- Name: idx_int_sync_conn; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_int_sync_conn ON public.integration_sync_logs USING btree (connection_id);


--
-- TOC entry 6194 (class 1259 OID 31261)
-- Name: idx_int_sync_flow; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_int_sync_flow ON public.integration_sync_logs USING btree (flow_id);


--
-- TOC entry 6195 (class 1259 OID 31262)
-- Name: idx_int_sync_status; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_int_sync_status ON public.integration_sync_logs USING btree (status);


--
-- TOC entry 6196 (class 1259 OID 31263)
-- Name: idx_int_sync_time; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_int_sync_time ON public.integration_sync_logs USING btree (start_time);


--
-- TOC entry 6174 (class 1259 OID 32956)
-- Name: idx_integration_connectors_category; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_integration_connectors_category ON public.integration_connectors USING btree (category);


--
-- TOC entry 6175 (class 1259 OID 32958)
-- Name: idx_integration_connectors_region; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_integration_connectors_region ON public.integration_connectors USING btree (region);


--
-- TOC entry 6176 (class 1259 OID 32959)
-- Name: idx_integration_connectors_sort; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_integration_connectors_sort ON public.integration_connectors USING btree (sort_order);


--
-- TOC entry 6177 (class 1259 OID 32957)
-- Name: idx_integration_connectors_status; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_integration_connectors_status ON public.integration_connectors USING btree (status);


--
-- TOC entry 6368 (class 1259 OID 33789)
-- Name: idx_job_name; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_job_name ON public.scheduled_job_executions USING btree (job_name);


--
-- TOC entry 6369 (class 1259 OID 33791)
-- Name: idx_job_started; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_job_started ON public.scheduled_job_executions USING btree (started_at);


--
-- TOC entry 6370 (class 1259 OID 33790)
-- Name: idx_job_status; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_job_status ON public.scheduled_job_executions USING btree (status);


--
-- TOC entry 6373 (class 1259 OID 33824)
-- Name: idx_jurisdiction_country; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_jurisdiction_country ON public.tax_jurisdictions USING btree (country_code);


--
-- TOC entry 6374 (class 1259 OID 33825)
-- Name: idx_jurisdiction_status; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_jurisdiction_status ON public.tax_jurisdictions USING btree (status);


--
-- TOC entry 6470 (class 1259 OID 50885)
-- Name: idx_le_bancs_customer; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_le_bancs_customer ON public.legal_entities USING btree (bancs_customer_id);


--
-- TOC entry 6471 (class 1259 OID 50873)
-- Name: idx_le_bank_customer; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_le_bank_customer ON public.legal_entities USING btree (is_bank_customer) WHERE (is_bank_customer = true);


--
-- TOC entry 6472 (class 1259 OID 51108)
-- Name: idx_le_can_lend; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_le_can_lend ON public.legal_entities USING btree (can_lend) WHERE (can_lend = true);


--
-- TOC entry 6473 (class 1259 OID 50537)
-- Name: idx_le_corporate; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_le_corporate ON public.legal_entities USING btree (corporate_id);


--
-- TOC entry 6474 (class 1259 OID 50538)
-- Name: idx_le_country; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_le_country ON public.legal_entities USING btree (country_code);


--
-- TOC entry 6475 (class 1259 OID 50543)
-- Name: idx_le_functional_currency; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_le_functional_currency ON public.legal_entities USING btree (functional_currency);


--
-- TOC entry 6476 (class 1259 OID 50903)
-- Name: idx_le_hierarchy_node; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_le_hierarchy_node ON public.legal_entities USING btree (hierarchy_node_id);


--
-- TOC entry 6477 (class 1259 OID 50541)
-- Name: idx_le_hierarchy_path; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_le_hierarchy_path ON public.legal_entities USING btree (hierarchy_path);


--
-- TOC entry 6478 (class 1259 OID 51106)
-- Name: idx_le_ihb_enabled; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_le_ihb_enabled ON public.legal_entities USING btree (ihb_enabled) WHERE (ihb_enabled = true);


--
-- TOC entry 6479 (class 1259 OID 50905)
-- Name: idx_le_mirrors_init; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_le_mirrors_init ON public.legal_entities USING btree (currency_mirrors_initialized);


--
-- TOC entry 6480 (class 1259 OID 50536)
-- Name: idx_le_parent; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_le_parent ON public.legal_entities USING btree (parent_entity_id);


--
-- TOC entry 6481 (class 1259 OID 50904)
-- Name: idx_le_program; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_le_program ON public.legal_entities USING btree (program_id);


--
-- TOC entry 6482 (class 1259 OID 51107)
-- Name: idx_le_settlement_va; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_le_settlement_va ON public.legal_entities USING btree (settlement_va_id) WHERE (settlement_va_id IS NOT NULL);


--
-- TOC entry 6483 (class 1259 OID 50539)
-- Name: idx_le_status; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_le_status ON public.legal_entities USING btree (status);


--
-- TOC entry 6484 (class 1259 OID 50542)
-- Name: idx_le_treasury_center; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_le_treasury_center ON public.legal_entities USING btree (is_treasury_center);


--
-- TOC entry 6485 (class 1259 OID 50540)
-- Name: idx_le_type; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_le_type ON public.legal_entities USING btree (entity_type);


--
-- TOC entry 6486 (class 1259 OID 51140)
-- Name: idx_legal_entity_ihb_interest_config; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_legal_entity_ihb_interest_config ON public.legal_entities USING btree (ihb_interest_config_id) WHERE (ihb_interest_config_id IS NOT NULL);


--
-- TOC entry 6227 (class 1259 OID 33068)
-- Name: idx_level_configs_program_id; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_level_configs_program_id ON public.hierarchy_level_configs USING btree (program_id);


--
-- TOC entry 6329 (class 1259 OID 33534)
-- Name: idx_line_receivable; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_line_receivable ON public.receivable_line_items USING btree (receivable_id);


--
-- TOC entry 6096 (class 1259 OID 30924)
-- Name: idx_netting_cycles_period; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_netting_cycles_period ON public.netting_cycles USING btree (period_start, period_end);


--
-- TOC entry 6097 (class 1259 OID 30923)
-- Name: idx_netting_cycles_status; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_netting_cycles_status ON public.netting_cycles USING btree (status);


--
-- TOC entry 6102 (class 1259 OID 30949)
-- Name: idx_netting_entries_cycle; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_netting_entries_cycle ON public.netting_entries USING btree (cycle_id);


--
-- TOC entry 6103 (class 1259 OID 30951)
-- Name: idx_netting_entries_payee; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_netting_entries_payee ON public.netting_entries USING btree (payee_entity_id);


--
-- TOC entry 6104 (class 1259 OID 30950)
-- Name: idx_netting_entries_payer; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_netting_entries_payer ON public.netting_entries USING btree (payer_entity_id);


--
-- TOC entry 6107 (class 1259 OID 30973)
-- Name: idx_netting_settle_cycle; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_netting_settle_cycle ON public.netting_settlements USING btree (cycle_id);


--
-- TOC entry 6108 (class 1259 OID 30974)
-- Name: idx_netting_settle_entity; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_netting_settle_entity ON public.netting_settlements USING btree (entity_id);


--
-- TOC entry 6247 (class 1259 OID 33143)
-- Name: idx_node_balance_history_date; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_node_balance_history_date ON public.hierarchy_node_balance_history USING btree (node_id, snapshot_date);


--
-- TOC entry 6248 (class 1259 OID 33142)
-- Name: idx_node_balance_history_node; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_node_balance_history_node ON public.hierarchy_node_balance_history USING btree (node_id);


--
-- TOC entry 6249 (class 1259 OID 33144)
-- Name: idx_node_balance_history_program; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_node_balance_history_program ON public.hierarchy_node_balance_history USING btree (program_id, snapshot_at);


--
-- TOC entry 6356 (class 1259 OID 33746)
-- Name: idx_notif_corporate; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_notif_corporate ON public.notifications USING btree (corporate_id);


--
-- TOC entry 6357 (class 1259 OID 33751)
-- Name: idx_notif_created; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_notif_created ON public.notifications USING btree (created_at);


--
-- TOC entry 6358 (class 1259 OID 33750)
-- Name: idx_notif_reference; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_notif_reference ON public.notifications USING btree (reference_type, reference_id);


--
-- TOC entry 6359 (class 1259 OID 33749)
-- Name: idx_notif_status; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_notif_status ON public.notifications USING btree (delivery_status);


--
-- TOC entry 6360 (class 1259 OID 33748)
-- Name: idx_notif_type; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_notif_type ON public.notifications USING btree (notification_type);


--
-- TOC entry 6361 (class 1259 OID 33747)
-- Name: idx_notif_user; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_notif_user ON public.notifications USING btree (user_id);


--
-- TOC entry 6080 (class 1259 OID 30850)
-- Name: idx_notional_pools_currency; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_notional_pools_currency ON public.notional_pools USING btree (pool_currency);


--
-- TOC entry 6081 (class 1259 OID 30849)
-- Name: idx_notional_pools_status; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_notional_pools_status ON public.notional_pools USING btree (status);


--
-- TOC entry 5968 (class 1259 OID 32945)
-- Name: idx_pa_account_number; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_pa_account_number ON public.physical_accounts USING btree (account_number);


--
-- TOC entry 5969 (class 1259 OID 32927)
-- Name: idx_pa_bank_code; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_pa_bank_code ON public.physical_accounts USING btree (bank_code);


--
-- TOC entry 5970 (class 1259 OID 32929)
-- Name: idx_pa_bank_relationship; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_pa_bank_relationship ON public.physical_accounts USING btree (bank_relationship);


--
-- TOC entry 5971 (class 1259 OID 32934)
-- Name: idx_pa_consent_expires; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_pa_consent_expires ON public.physical_accounts USING btree (consent_expires_at);


--
-- TOC entry 5972 (class 1259 OID 32947)
-- Name: idx_pa_corporate; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_pa_corporate ON public.physical_accounts USING btree (corporate_id);


--
-- TOC entry 5973 (class 1259 OID 32928)
-- Name: idx_pa_currency; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_pa_currency ON public.physical_accounts USING btree (currency_code);


--
-- TOC entry 5974 (class 1259 OID 32930)
-- Name: idx_pa_data_source; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_pa_data_source ON public.physical_accounts USING btree (data_source);


--
-- TOC entry 5975 (class 1259 OID 32946)
-- Name: idx_pa_iban; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_pa_iban ON public.physical_accounts USING btree (iban);


--
-- TOC entry 5976 (class 1259 OID 50917)
-- Name: idx_pa_legal_entity; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_pa_legal_entity ON public.physical_accounts USING btree (legal_entity_id);


--
-- TOC entry 5977 (class 1259 OID 32931)
-- Name: idx_pa_pooling; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_pa_pooling ON public.physical_accounts USING btree (pooling_enabled);


--
-- TOC entry 5978 (class 1259 OID 50860)
-- Name: idx_pa_shadow_va; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_pa_shadow_va ON public.physical_accounts USING btree (shadow_va_id);


--
-- TOC entry 5979 (class 1259 OID 32948)
-- Name: idx_pa_status; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_pa_status ON public.physical_accounts USING btree (status);


--
-- TOC entry 5980 (class 1259 OID 32932)
-- Name: idx_pa_sweep; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_pa_sweep ON public.physical_accounts USING btree (sweep_enabled);


--
-- TOC entry 5981 (class 1259 OID 32933)
-- Name: idx_pa_sync_status; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_pa_sync_status ON public.physical_accounts USING btree (sync_status);


--
-- TOC entry 6201 (class 1259 OID 32837)
-- Name: idx_party_code; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_party_code ON public.parties USING btree (party_code);


--
-- TOC entry 6202 (class 1259 OID 32838)
-- Name: idx_party_corporate; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_party_corporate ON public.parties USING btree (corporate_id);


--
-- TOC entry 6203 (class 1259 OID 51186)
-- Name: idx_party_ic_lookup; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_party_ic_lookup ON public.parties USING btree (corporate_id, is_intercompany, linked_legal_entity_id) WHERE (is_intercompany = true);


--
-- TOC entry 6204 (class 1259 OID 51181)
-- Name: idx_party_intercompany; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_party_intercompany ON public.parties USING btree (is_intercompany) WHERE (is_intercompany = true);


--
-- TOC entry 6205 (class 1259 OID 32840)
-- Name: idx_party_kyc_status; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_party_kyc_status ON public.parties USING btree (kyc_status);


--
-- TOC entry 6206 (class 1259 OID 51182)
-- Name: idx_party_linked_entity; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_party_linked_entity ON public.parties USING btree (linked_legal_entity_id) WHERE (linked_legal_entity_id IS NOT NULL);


--
-- TOC entry 6207 (class 1259 OID 51184)
-- Name: idx_party_netting_eligible; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_party_netting_eligible ON public.parties USING btree (netting_eligible) WHERE (netting_eligible = true);


--
-- TOC entry 6208 (class 1259 OID 51180)
-- Name: idx_party_owning_entity; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_party_owning_entity ON public.parties USING btree (owning_entity_id) WHERE (owning_entity_id IS NOT NULL);


--
-- TOC entry 6209 (class 1259 OID 51183)
-- Name: idx_party_pobo_eligible; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_party_pobo_eligible ON public.parties USING btree (pobo_eligible) WHERE (pobo_eligible = true);


--
-- TOC entry 6210 (class 1259 OID 51185)
-- Name: idx_party_pobo_vendor_lookup; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_party_pobo_vendor_lookup ON public.parties USING btree (corporate_id, owning_entity_id, pobo_eligible, status) WHERE ((roles)::text ~~ '%VENDOR%'::text);


--
-- TOC entry 6211 (class 1259 OID 32839)
-- Name: idx_party_status; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_party_status ON public.parties USING btree (status);


--
-- TOC entry 6555 (class 1259 OID 51579)
-- Name: idx_payable_batch; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_payable_batch ON public.payables USING btree (payment_batch_id);


--
-- TOC entry 6556 (class 1259 OID 51571)
-- Name: idx_payable_corporate; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_payable_corporate ON public.payables USING btree (corporate_id);


--
-- TOC entry 6557 (class 1259 OID 51586)
-- Name: idx_payable_counterparty; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_payable_counterparty ON public.payables USING btree (counterparty_entity_id);


--
-- TOC entry 6558 (class 1259 OID 51575)
-- Name: idx_payable_due_date; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_payable_due_date ON public.payables USING btree (due_date);


--
-- TOC entry 6559 (class 1259 OID 51578)
-- Name: idx_payable_hierarchy; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_payable_hierarchy ON public.payables USING btree (hierarchy_node_id);


--
-- TOC entry 6560 (class 1259 OID 51584)
-- Name: idx_payable_intercompany; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_payable_intercompany ON public.payables USING btree (is_intercompany);


--
-- TOC entry 6561 (class 1259 OID 51585)
-- Name: idx_payable_netting_cycle; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_payable_netting_cycle ON public.payables USING btree (netting_cycle_id);


--
-- TOC entry 6562 (class 1259 OID 51580)
-- Name: idx_payable_owning_entity; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_payable_owning_entity ON public.payables USING btree (owning_entity_id);


--
-- TOC entry 6563 (class 1259 OID 51581)
-- Name: idx_payable_party; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_payable_party ON public.payables USING btree (party_id);


--
-- TOC entry 6564 (class 1259 OID 51582)
-- Name: idx_payable_payment_route; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_payable_payment_route ON public.payables USING btree (payment_route);


--
-- TOC entry 6565 (class 1259 OID 51583)
-- Name: idx_payable_pobo_status; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_payable_pobo_status ON public.payables USING btree (pobo_request_status);


--
-- TOC entry 6566 (class 1259 OID 51572)
-- Name: idx_payable_program; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_payable_program ON public.payables USING btree (program_id);


--
-- TOC entry 6567 (class 1259 OID 51574)
-- Name: idx_payable_status; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_payable_status ON public.payables USING btree (status);


--
-- TOC entry 6568 (class 1259 OID 51577)
-- Name: idx_payable_type; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_payable_type ON public.payables USING btree (payable_type);


--
-- TOC entry 6569 (class 1259 OID 51573)
-- Name: idx_payable_va; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_payable_va ON public.payables USING btree (virtual_account_id);


--
-- TOC entry 6570 (class 1259 OID 51576)
-- Name: idx_payable_vendor; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_payable_vendor ON public.payables USING btree (vendor_id);


--
-- TOC entry 6216 (class 1259 OID 32844)
-- Name: idx_pba_iban; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_pba_iban ON public.party_bank_accounts USING btree (iban);


--
-- TOC entry 6217 (class 1259 OID 32843)
-- Name: idx_pba_party; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_pba_party ON public.party_bank_accounts USING btree (party_id);


--
-- TOC entry 6462 (class 1259 OID 42315)
-- Name: idx_pco_charge_code; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_pco_charge_code ON public.program_charge_overrides USING btree (charge_code);


--
-- TOC entry 6463 (class 1259 OID 42314)
-- Name: idx_pco_program; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_pco_program ON public.program_charge_overrides USING btree (program_id);


--
-- TOC entry 6464 (class 1259 OID 42316)
-- Name: idx_pco_program_charge; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_pco_program_charge ON public.program_charge_overrides USING btree (program_id, charge_code);


--
-- TOC entry 6465 (class 1259 OID 42317)
-- Name: idx_pco_status; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_pco_status ON public.program_charge_overrides USING btree (status);


--
-- TOC entry 6220 (class 1259 OID 32845)
-- Name: idx_pdoc_party; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_pdoc_party ON public.party_documents USING btree (party_id);


--
-- TOC entry 6221 (class 1259 OID 32847)
-- Name: idx_pdoc_status; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_pdoc_status ON public.party_documents USING btree (verification_status);


--
-- TOC entry 6222 (class 1259 OID 32846)
-- Name: idx_pdoc_type; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_pdoc_type ON public.party_documents USING btree (document_type);


--
-- TOC entry 6420 (class 1259 OID 34030)
-- Name: idx_pobo_auth_behalf; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_pobo_auth_behalf ON public.pobo_authorizations USING btree (behalf_entity_id);


--
-- TOC entry 6421 (class 1259 OID 34029)
-- Name: idx_pobo_auth_payer; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_pobo_auth_payer ON public.pobo_authorizations USING btree (payer_entity_id);


--
-- TOC entry 6422 (class 1259 OID 34031)
-- Name: idx_pobo_auth_status; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_pobo_auth_status ON public.pobo_authorizations USING btree (status);


--
-- TOC entry 6091 (class 1259 OID 30897)
-- Name: idx_pool_calc_date; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_pool_calc_date ON public.pool_interest_calculations USING btree (calculation_date);


--
-- TOC entry 6092 (class 1259 OID 30896)
-- Name: idx_pool_calc_pool; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_pool_calc_pool ON public.pool_interest_calculations USING btree (pool_id);


--
-- TOC entry 6093 (class 1259 OID 30898)
-- Name: idx_pool_calc_status; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_pool_calc_status ON public.pool_interest_calculations USING btree (status);


--
-- TOC entry 6086 (class 1259 OID 30874)
-- Name: idx_pool_members_account; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_pool_members_account ON public.pool_members USING btree (account_id);


--
-- TOC entry 6087 (class 1259 OID 30873)
-- Name: idx_pool_members_pool; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_pool_members_pool ON public.pool_members USING btree (pool_id);


--
-- TOC entry 6088 (class 1259 OID 30875)
-- Name: idx_pool_members_status; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_pool_members_status ON public.pool_members USING btree (status);


--
-- TOC entry 5986 (class 1259 OID 32898)
-- Name: idx_program_code; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_program_code ON public.unified_programs USING btree (program_code);


--
-- TOC entry 5987 (class 1259 OID 32899)
-- Name: idx_program_corporate; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_program_corporate ON public.unified_programs USING btree (corporate_id);


--
-- TOC entry 5988 (class 1259 OID 32901)
-- Name: idx_program_status; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_program_status ON public.unified_programs USING btree (status);


--
-- TOC entry 5989 (class 1259 OID 32900)
-- Name: idx_program_type; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_program_type ON public.unified_programs USING btree (program_type);


--
-- TOC entry 5990 (class 1259 OID 50908)
-- Name: idx_programs_legal_entity; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_programs_legal_entity ON public.unified_programs USING btree (legal_entity_id);


--
-- TOC entry 6291 (class 1259 OID 33466)
-- Name: idx_receivable_channel; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_receivable_channel ON public.receivables USING btree (collection_channel);


--
-- TOC entry 6292 (class 1259 OID 51600)
-- Name: idx_receivable_cobo; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_receivable_cobo ON public.receivables USING btree (is_cobo) WHERE (is_cobo = true);


--
-- TOC entry 6293 (class 1259 OID 51601)
-- Name: idx_receivable_cobo_collector; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_receivable_cobo_collector ON public.receivables USING btree (cobo_collector_entity_id) WHERE (cobo_collector_entity_id IS NOT NULL);


--
-- TOC entry 6294 (class 1259 OID 51602)
-- Name: idx_receivable_cobo_status; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_receivable_cobo_status ON public.receivables USING btree (cobo_request_status) WHERE (cobo_request_status IS NOT NULL);


--
-- TOC entry 6295 (class 1259 OID 51606)
-- Name: idx_receivable_collection_route; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_receivable_collection_route ON public.receivables USING btree (collection_route);


--
-- TOC entry 6296 (class 1259 OID 33455)
-- Name: idx_receivable_corporate; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_receivable_corporate ON public.receivables USING btree (corporate_id);


--
-- TOC entry 6297 (class 1259 OID 33462)
-- Name: idx_receivable_customer; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_receivable_customer ON public.receivables USING btree (customer_id);


--
-- TOC entry 6298 (class 1259 OID 51597)
-- Name: idx_receivable_customer_party; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_receivable_customer_party ON public.receivables USING btree (customer_party_id);


--
-- TOC entry 6299 (class 1259 OID 33461)
-- Name: idx_receivable_due_date; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_receivable_due_date ON public.receivables USING btree (due_date);


--
-- TOC entry 6300 (class 1259 OID 33468)
-- Name: idx_receivable_external; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_receivable_external ON public.receivables USING btree (external_reference);


--
-- TOC entry 6301 (class 1259 OID 33464)
-- Name: idx_receivable_hierarchy; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_receivable_hierarchy ON public.receivables USING btree (hierarchy_node_id);


--
-- TOC entry 6302 (class 1259 OID 33465)
-- Name: idx_receivable_hierarchy_path; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_receivable_hierarchy_path ON public.receivables USING btree (hierarchy_path);


--
-- TOC entry 6303 (class 1259 OID 51598)
-- Name: idx_receivable_intercompany; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_receivable_intercompany ON public.receivables USING btree (is_intercompany) WHERE (is_intercompany = true);


--
-- TOC entry 6304 (class 1259 OID 51599)
-- Name: idx_receivable_intercompany_entity; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_receivable_intercompany_entity ON public.receivables USING btree (intercompany_entity_id) WHERE (intercompany_entity_id IS NOT NULL);


--
-- TOC entry 6305 (class 1259 OID 51603)
-- Name: idx_receivable_netting_cycle; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_receivable_netting_cycle ON public.receivables USING btree (netting_cycle_id) WHERE (netting_cycle_id IS NOT NULL);


--
-- TOC entry 6306 (class 1259 OID 51604)
-- Name: idx_receivable_netting_eligible; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_receivable_netting_eligible ON public.receivables USING btree (netting_eligible) WHERE (netting_eligible = true);


--
-- TOC entry 6307 (class 1259 OID 51605)
-- Name: idx_receivable_netting_status; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_receivable_netting_status ON public.receivables USING btree (netting_status) WHERE ((netting_status)::text <> 'NOT_INCLUDED'::text);


--
-- TOC entry 6308 (class 1259 OID 51596)
-- Name: idx_receivable_owning_entity; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_receivable_owning_entity ON public.receivables USING btree (owning_entity_id);


--
-- TOC entry 6309 (class 1259 OID 33467)
-- Name: idx_receivable_platform; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_receivable_platform ON public.receivables USING btree (platform);


--
-- TOC entry 6310 (class 1259 OID 33456)
-- Name: idx_receivable_program; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_receivable_program ON public.receivables USING btree (program_id);


--
-- TOC entry 6311 (class 1259 OID 33460)
-- Name: idx_receivable_status; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_receivable_status ON public.receivables USING btree (status);


--
-- TOC entry 6312 (class 1259 OID 33463)
-- Name: idx_receivable_type; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_receivable_type ON public.receivables USING btree (receivable_type);


--
-- TOC entry 6313 (class 1259 OID 33457)
-- Name: idx_receivable_va; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_receivable_va ON public.receivables USING btree (virtual_account_id);


--
-- TOC entry 6314 (class 1259 OID 33458)
-- Name: idx_receivable_viban; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_receivable_viban ON public.receivables USING btree (primary_viban_id);


--
-- TOC entry 6315 (class 1259 OID 33459)
-- Name: idx_receivable_viban_str; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_receivable_viban_str ON public.receivables USING btree (viban);


--
-- TOC entry 6411 (class 1259 OID 33994)
-- Name: idx_recharge_behalf; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_recharge_behalf ON public.intercompany_recharges USING btree (behalf_entity_id);


--
-- TOC entry 6412 (class 1259 OID 33995)
-- Name: idx_recharge_payable; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_recharge_payable ON public.intercompany_recharges USING btree (original_payable_id);


--
-- TOC entry 6413 (class 1259 OID 33993)
-- Name: idx_recharge_payer; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_recharge_payer ON public.intercompany_recharges USING btree (payer_entity_id);


--
-- TOC entry 6414 (class 1259 OID 33997)
-- Name: idx_recharge_settlement; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_recharge_settlement ON public.intercompany_recharges USING btree (settlement_date);


--
-- TOC entry 6415 (class 1259 OID 33996)
-- Name: idx_recharge_status; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_recharge_status ON public.intercompany_recharges USING btree (status);


--
-- TOC entry 6320 (class 1259 OID 33509)
-- Name: idx_recv_payment_date; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_recv_payment_date ON public.receivable_payments USING btree (payment_date);


--
-- TOC entry 6321 (class 1259 OID 33511)
-- Name: idx_recv_payment_match_type; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_recv_payment_match_type ON public.receivable_payments USING btree (match_type);


--
-- TOC entry 6322 (class 1259 OID 33506)
-- Name: idx_recv_payment_receivable; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_recv_payment_receivable ON public.receivable_payments USING btree (receivable_id);


--
-- TOC entry 6323 (class 1259 OID 33512)
-- Name: idx_recv_payment_reference; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_recv_payment_reference ON public.receivable_payments USING btree (payment_reference);


--
-- TOC entry 6324 (class 1259 OID 33510)
-- Name: idx_recv_payment_status; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_recv_payment_status ON public.receivable_payments USING btree (status);


--
-- TOC entry 6325 (class 1259 OID 33507)
-- Name: idx_recv_payment_transaction; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_recv_payment_transaction ON public.receivable_payments USING btree (transaction_id);


--
-- TOC entry 6326 (class 1259 OID 33508)
-- Name: idx_recv_payment_viban; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_recv_payment_viban ON public.receivable_payments USING btree (viban_id);


--
-- TOC entry 6071 (class 1259 OID 51147)
-- Name: idx_sweep_exec_ihb_deposit; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_sweep_exec_ihb_deposit ON public.sweep_executions USING btree (ihb_deposit_id);


--
-- TOC entry 6072 (class 1259 OID 51148)
-- Name: idx_sweep_exec_ihb_enabled; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_sweep_exec_ihb_enabled ON public.sweep_executions USING btree (ihb_enabled) WHERE (ihb_enabled = true);


--
-- TOC entry 6073 (class 1259 OID 30823)
-- Name: idx_sweep_exec_rule; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_sweep_exec_rule ON public.sweep_executions USING btree (rule_id);


--
-- TOC entry 6074 (class 1259 OID 30824)
-- Name: idx_sweep_exec_status; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_sweep_exec_status ON public.sweep_executions USING btree (status);


--
-- TOC entry 6075 (class 1259 OID 30825)
-- Name: idx_sweep_exec_time; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_sweep_exec_time ON public.sweep_executions USING btree (execution_time);


--
-- TOC entry 6060 (class 1259 OID 30777)
-- Name: idx_sweep_rules_frequency; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_sweep_rules_frequency ON public.sweep_rules USING btree (frequency);


--
-- TOC entry 6061 (class 1259 OID 30775)
-- Name: idx_sweep_rules_status; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_sweep_rules_status ON public.sweep_rules USING btree (status);


--
-- TOC entry 6062 (class 1259 OID 30776)
-- Name: idx_sweep_rules_target; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_sweep_rules_target ON public.sweep_rules USING btree (target_account_id);


--
-- TOC entry 6067 (class 1259 OID 30796)
-- Name: idx_sweep_sources_account; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_sweep_sources_account ON public.sweep_rule_sources USING btree (account_id);


--
-- TOC entry 6068 (class 1259 OID 30795)
-- Name: idx_sweep_sources_rule; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_sweep_sources_rule ON public.sweep_rule_sources USING btree (rule_id);


--
-- TOC entry 6379 (class 1259 OID 33862)
-- Name: idx_tax_config_category; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_tax_config_category ON public.tax_configurations USING btree (tax_category);


--
-- TOC entry 6380 (class 1259 OID 33860)
-- Name: idx_tax_config_jurisdiction; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_tax_config_jurisdiction ON public.tax_configurations USING btree (jurisdiction_id);


--
-- TOC entry 6381 (class 1259 OID 33861)
-- Name: idx_tax_config_status; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_tax_config_status ON public.tax_configurations USING btree (status);


--
-- TOC entry 6382 (class 1259 OID 33859)
-- Name: idx_tax_config_type; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_tax_config_type ON public.tax_configurations USING btree (tax_type);


--
-- TOC entry 6383 (class 1259 OID 33863)
-- Name: idx_tax_config_withholding; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_tax_config_withholding ON public.tax_configurations USING btree (is_withholding);


--
-- TOC entry 5995 (class 1259 OID 33363)
-- Name: idx_txn_auto_reconciled; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_txn_auto_reconciled ON public.va_movements USING btree (auto_reconciled, transaction_date) WHERE (auto_reconciled = true);


--
-- TOC entry 5996 (class 1259 OID 31288)
-- Name: idx_txn_corporate_id; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_txn_corporate_id ON public.va_movements USING btree (corporate_id);


--
-- TOC entry 5997 (class 1259 OID 32904)
-- Name: idx_txn_correlation; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_txn_correlation ON public.va_movements USING btree (correlation_id);


--
-- TOC entry 5998 (class 1259 OID 31291)
-- Name: idx_txn_date; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_txn_date ON public.va_movements USING btree (transaction_date);


--
-- TOC entry 5999 (class 1259 OID 33364)
-- Name: idx_txn_hierarchy_path; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_txn_hierarchy_path ON public.va_movements USING btree (hierarchy_path varchar_pattern_ops) WHERE (hierarchy_path IS NOT NULL);


--
-- TOC entry 6000 (class 1259 OID 32902)
-- Name: idx_txn_movement_type; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_txn_movement_type ON public.va_movements USING btree (movement_type);


--
-- TOC entry 6001 (class 1259 OID 33365)
-- Name: idx_txn_pobo; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_txn_pobo ON public.va_movements USING btree (is_pobo, transaction_date) WHERE (is_pobo = true);


--
-- TOC entry 6002 (class 1259 OID 33393)
-- Name: idx_txn_program_id; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_txn_program_id ON public.va_movements USING btree (program_id);


--
-- TOC entry 6003 (class 1259 OID 33361)
-- Name: idx_txn_reconciled; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_txn_reconciled ON public.va_movements USING btree (reconciled_reference_type, reconciled_reference_id) WHERE (reconciled_reference_id IS NOT NULL);


--
-- TOC entry 6004 (class 1259 OID 31290)
-- Name: idx_txn_reference; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_txn_reference ON public.va_movements USING btree (reference_number);


--
-- TOC entry 6005 (class 1259 OID 33366)
-- Name: idx_txn_robo; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_txn_robo ON public.va_movements USING btree (is_robo, transaction_date) WHERE (is_robo = true);


--
-- TOC entry 6006 (class 1259 OID 33362)
-- Name: idx_txn_routed_viban; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_txn_routed_viban ON public.va_movements USING btree (routed_via_viban, transaction_date) WHERE (routed_via_viban = true);


--
-- TOC entry 6007 (class 1259 OID 32903)
-- Name: idx_txn_status; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_txn_status ON public.va_movements USING btree (status);


--
-- TOC entry 6008 (class 1259 OID 31289)
-- Name: idx_txn_va_id; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_txn_va_id ON public.va_movements USING btree (va_id);


--
-- TOC entry 6009 (class 1259 OID 33359)
-- Name: idx_txn_viban; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_txn_viban ON public.va_movements USING btree (viban) WHERE (viban IS NOT NULL);


--
-- TOC entry 6010 (class 1259 OID 33360)
-- Name: idx_txn_viban_id; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_txn_viban_id ON public.va_movements USING btree (viban_id) WHERE (viban_id IS NOT NULL);


--
-- TOC entry 6332 (class 1259 OID 34207)
-- Name: idx_unmatched_bank_ref; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_unmatched_bank_ref ON public.unmatched_payments USING btree (bank_reference);


--
-- TOC entry 6333 (class 1259 OID 34210)
-- Name: idx_unmatched_created; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_unmatched_created ON public.unmatched_payments USING btree (created_at);


--
-- TOC entry 6334 (class 1259 OID 34208)
-- Name: idx_unmatched_exception; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_unmatched_exception ON public.unmatched_payments USING btree (exception_transaction_id);


--
-- TOC entry 6335 (class 1259 OID 33573)
-- Name: idx_unmatched_payment_date; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_unmatched_payment_date ON public.unmatched_payments USING btree (payment_date);


--
-- TOC entry 6336 (class 1259 OID 34209)
-- Name: idx_unmatched_priority; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_unmatched_priority ON public.unmatched_payments USING btree (priority);


--
-- TOC entry 6337 (class 1259 OID 34206)
-- Name: idx_unmatched_program; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_unmatched_program ON public.unmatched_payments USING btree (program_id);


--
-- TOC entry 6338 (class 1259 OID 33572)
-- Name: idx_unmatched_status; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_unmatched_status ON public.unmatched_payments USING btree (status);


--
-- TOC entry 6339 (class 1259 OID 33574)
-- Name: idx_unmatched_va; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_unmatched_va ON public.unmatched_payments USING btree (virtual_account_id);


--
-- TOC entry 6340 (class 1259 OID 33575)
-- Name: idx_unmatched_viban; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_unmatched_viban ON public.unmatched_payments USING btree (viban_id);


--
-- TOC entry 6018 (class 1259 OID 50478)
-- Name: idx_va_account_category; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_va_account_category ON public.virtual_accounts USING btree (account_category);


--
-- TOC entry 6019 (class 1259 OID 50477)
-- Name: idx_va_account_type; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_va_account_type ON public.virtual_accounts USING btree (account_type);


--
-- TOC entry 6020 (class 1259 OID 50484)
-- Name: idx_va_bank_account_number; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_va_bank_account_number ON public.virtual_accounts USING btree (bank_account_number);


--
-- TOC entry 6021 (class 1259 OID 50481)
-- Name: idx_va_base_currency; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_va_base_currency ON public.virtual_accounts USING btree (base_currency);


--
-- TOC entry 6022 (class 1259 OID 33354)
-- Name: idx_va_budget_owner; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_va_budget_owner ON public.virtual_accounts USING btree (budget_owner_id) WHERE (budget_owner_id IS NOT NULL);


--
-- TOC entry 6023 (class 1259 OID 33353)
-- Name: idx_va_card_program; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_va_card_program ON public.virtual_accounts USING btree (card_program_type) WHERE (card_program_type IS NOT NULL);


--
-- TOC entry 6024 (class 1259 OID 33348)
-- Name: idx_va_collection_channel; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_va_collection_channel ON public.virtual_accounts USING btree (collection_channel) WHERE (collection_channel IS NOT NULL);


--
-- TOC entry 6025 (class 1259 OID 32908)
-- Name: idx_va_corporate; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_va_corporate ON public.virtual_accounts USING btree (corporate_id);


--
-- TOC entry 6026 (class 1259 OID 33395)
-- Name: idx_va_currency; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_va_currency ON public.virtual_accounts USING btree (currency_code);


--
-- TOC entry 6027 (class 1259 OID 33350)
-- Name: idx_va_expiry; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_va_expiry ON public.virtual_accounts USING btree (balance_expiry_date) WHERE (balance_expiry_date IS NOT NULL);


--
-- TOC entry 6028 (class 1259 OID 50485)
-- Name: idx_va_external_limit; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_va_external_limit ON public.virtual_accounts USING btree (external_limit_id);


--
-- TOC entry 6029 (class 1259 OID 33346)
-- Name: idx_va_hierarchy_node; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_va_hierarchy_node ON public.virtual_accounts USING btree (hierarchy_node_id);


--
-- TOC entry 6030 (class 1259 OID 50897)
-- Name: idx_va_hierarchy_node_category; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_va_hierarchy_node_category ON public.virtual_accounts USING btree (hierarchy_node_id, account_category);


--
-- TOC entry 6031 (class 1259 OID 50898)
-- Name: idx_va_hierarchy_node_category_currency; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_va_hierarchy_node_category_currency ON public.virtual_accounts USING btree (hierarchy_node_id, account_category, currency_code);


--
-- TOC entry 6032 (class 1259 OID 33352)
-- Name: idx_va_hierarchy_path; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_va_hierarchy_path ON public.virtual_accounts USING btree (hierarchy_path varchar_pattern_ops) WHERE (hierarchy_path IS NOT NULL);


--
-- TOC entry 6033 (class 1259 OID 50483)
-- Name: idx_va_hierarchy_path_va; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_va_hierarchy_path_va ON public.virtual_accounts USING btree (hierarchy_path_va);


--
-- TOC entry 6034 (class 1259 OID 34112)
-- Name: idx_va_hierarchy_special; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_va_hierarchy_special ON public.virtual_accounts USING btree (hierarchy_node_id, special_type) WHERE ((special_type)::text <> 'REGULAR'::text);


--
-- TOC entry 6035 (class 1259 OID 32911)
-- Name: idx_va_holder_party; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_va_holder_party ON public.virtual_accounts USING btree (holder_party_id);


--
-- TOC entry 6036 (class 1259 OID 51134)
-- Name: idx_va_ihb_sweep; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_va_ihb_sweep ON public.virtual_accounts USING btree (ihb_sweep_enabled) WHERE (ihb_sweep_enabled = true);


--
-- TOC entry 6037 (class 1259 OID 50486)
-- Name: idx_va_internal_limit; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_va_internal_limit ON public.virtual_accounts USING btree (internal_limit_id);


--
-- TOC entry 6038 (class 1259 OID 50482)
-- Name: idx_va_linked_physical; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_va_linked_physical ON public.virtual_accounts USING btree (linked_physical_account_id);


--
-- TOC entry 6039 (class 1259 OID 33351)
-- Name: idx_va_loyalty_tier; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_va_loyalty_tier ON public.virtual_accounts USING btree (loyalty_tier) WHERE (loyalty_tier IS NOT NULL);


--
-- TOC entry 6011 (class 1259 OID 31286)
-- Name: idx_va_movements_corp_date; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_va_movements_corp_date ON public.va_movements USING btree (corporate_id, transaction_date DESC);


--
-- TOC entry 6012 (class 1259 OID 31287)
-- Name: idx_va_movements_corp_status; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_va_movements_corp_status ON public.va_movements USING btree (corporate_id, status);


--
-- TOC entry 6013 (class 1259 OID 31285)
-- Name: idx_va_movements_corporate_id; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_va_movements_corporate_id ON public.va_movements USING btree (corporate_id);


--
-- TOC entry 6040 (class 1259 OID 32905)
-- Name: idx_va_number; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_va_number ON public.virtual_accounts USING btree (va_number);


--
-- TOC entry 6041 (class 1259 OID 50480)
-- Name: idx_va_owning_entity; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_va_owning_entity ON public.virtual_accounts USING btree (owning_entity_id);


--
-- TOC entry 6042 (class 1259 OID 50479)
-- Name: idx_va_parent_account; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_va_parent_account ON public.virtual_accounts USING btree (parent_account_id);


--
-- TOC entry 6043 (class 1259 OID 50899)
-- Name: idx_va_parent_account_category; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_va_parent_account_category ON public.virtual_accounts USING btree (parent_account_id, account_category);


--
-- TOC entry 6044 (class 1259 OID 33394)
-- Name: idx_va_physical; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_va_physical ON public.virtual_accounts USING btree (physical_account_id);


--
-- TOC entry 6045 (class 1259 OID 33347)
-- Name: idx_va_primary_viban; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_va_primary_viban ON public.virtual_accounts USING btree (primary_viban_id);


--
-- TOC entry 6046 (class 1259 OID 32907)
-- Name: idx_va_program; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_va_program ON public.virtual_accounts USING btree (program_id);


--
-- TOC entry 6047 (class 1259 OID 50900)
-- Name: idx_va_program_category; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_va_program_category ON public.virtual_accounts USING btree (program_id, account_category);


--
-- TOC entry 6048 (class 1259 OID 50901)
-- Name: idx_va_program_currency_category; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_va_program_currency_category ON public.virtual_accounts USING btree (program_id, currency_code, account_category);


--
-- TOC entry 6049 (class 1259 OID 34111)
-- Name: idx_va_special_type; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_va_special_type ON public.virtual_accounts USING btree (program_id, currency_code, special_type) WHERE ((special_type)::text = ANY ((ARRAY['SETTLEMENT'::character varying, 'EXCEPTION'::character varying])::text[]));


--
-- TOC entry 6050 (class 1259 OID 32909)
-- Name: idx_va_status; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_va_status ON public.virtual_accounts USING btree (status);


--
-- TOC entry 6051 (class 1259 OID 33349)
-- Name: idx_va_value_type; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_va_value_type ON public.virtual_accounts USING btree (value_type);


--
-- TOC entry 6052 (class 1259 OID 32906)
-- Name: idx_va_viban; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_va_viban ON public.virtual_accounts USING btree (viban);


--
-- TOC entry 6053 (class 1259 OID 32910)
-- Name: idx_va_wallet_type; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_va_wallet_type ON public.virtual_accounts USING btree (wallet_type);


--
-- TOC entry 6250 (class 1259 OID 33195)
-- Name: idx_viban_pools_available; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_viban_pools_available ON public.viban_pools USING btree (available_count) WHERE ((status)::text = 'ACTIVE'::text);


--
-- TOC entry 6251 (class 1259 OID 33193)
-- Name: idx_viban_pools_program_id; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_viban_pools_program_id ON public.viban_pools USING btree (program_id);


--
-- TOC entry 6252 (class 1259 OID 33194)
-- Name: idx_viban_pools_status; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_viban_pools_status ON public.viban_pools USING btree (status);


--
-- TOC entry 6286 (class 1259 OID 33320)
-- Name: idx_viban_sequences_pool; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_viban_sequences_pool ON public.viban_sequences USING btree (pool_id);


--
-- TOC entry 6275 (class 1259 OID 33297)
-- Name: idx_viban_usage_match_type; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_viban_usage_match_type ON public.viban_usage_log USING btree (match_type);


--
-- TOC entry 6276 (class 1259 OID 33295)
-- Name: idx_viban_usage_program_id; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_viban_usage_program_id ON public.viban_usage_log USING btree (program_id);


--
-- TOC entry 6277 (class 1259 OID 33296)
-- Name: idx_viban_usage_received; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_viban_usage_received ON public.viban_usage_log USING btree (received_at);


--
-- TOC entry 6278 (class 1259 OID 33298)
-- Name: idx_viban_usage_status; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_viban_usage_status ON public.viban_usage_log USING btree (status);


--
-- TOC entry 6279 (class 1259 OID 33299)
-- Name: idx_viban_usage_transaction; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_viban_usage_transaction ON public.viban_usage_log USING btree (transaction_id) WHERE (transaction_id IS NOT NULL);


--
-- TOC entry 6280 (class 1259 OID 33300)
-- Name: idx_viban_usage_unmatched; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_viban_usage_unmatched ON public.viban_usage_log USING btree (program_id, status) WHERE ((status)::text = 'UNMATCHED'::text);


--
-- TOC entry 6281 (class 1259 OID 33294)
-- Name: idx_viban_usage_va_id; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_viban_usage_va_id ON public.viban_usage_log USING btree (virtual_account_id);


--
-- TOC entry 6282 (class 1259 OID 33293)
-- Name: idx_viban_usage_viban; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_viban_usage_viban ON public.viban_usage_log USING btree (viban);


--
-- TOC entry 6283 (class 1259 OID 33292)
-- Name: idx_viban_usage_viban_id; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_viban_usage_viban_id ON public.viban_usage_log USING btree (viban_id);


--
-- TOC entry 6257 (class 1259 OID 33251)
-- Name: idx_vibans_active; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_vibans_active ON public.vibans USING btree (virtual_account_id, status) WHERE ((status)::text = 'ACTIVE'::text);


--
-- TOC entry 6258 (class 1259 OID 33256)
-- Name: idx_vibans_customer; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_vibans_customer ON public.vibans USING btree (customer_reference) WHERE (customer_reference IS NOT NULL);


--
-- TOC entry 6259 (class 1259 OID 33254)
-- Name: idx_vibans_expiry; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_vibans_expiry ON public.vibans USING btree (valid_until) WHERE (((status)::text = 'ACTIVE'::text) AND (valid_until IS NOT NULL));


--
-- TOC entry 6260 (class 1259 OID 33246)
-- Name: idx_vibans_hierarchy_node_id; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_vibans_hierarchy_node_id ON public.vibans USING btree (hierarchy_node_id) WHERE (hierarchy_node_id IS NOT NULL);


--
-- TOC entry 6261 (class 1259 OID 33252)
-- Name: idx_vibans_pool_id; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_vibans_pool_id ON public.vibans USING btree (pool_id) WHERE (pool_id IS NOT NULL);


--
-- TOC entry 6262 (class 1259 OID 33255)
-- Name: idx_vibans_primary; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_vibans_primary ON public.vibans USING btree (virtual_account_id, is_primary) WHERE (is_primary = true);


--
-- TOC entry 6263 (class 1259 OID 33245)
-- Name: idx_vibans_program_id; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_vibans_program_id ON public.vibans USING btree (program_id);


--
-- TOC entry 6264 (class 1259 OID 33247)
-- Name: idx_vibans_reference; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_vibans_reference ON public.vibans USING btree (reference_type, reference_id) WHERE (reference_id IS NOT NULL);


--
-- TOC entry 6265 (class 1259 OID 33248)
-- Name: idx_vibans_reference_type; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_vibans_reference_type ON public.vibans USING btree (reference_type);


--
-- TOC entry 6266 (class 1259 OID 33253)
-- Name: idx_vibans_return_scheduled; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_vibans_return_scheduled ON public.vibans USING btree (return_scheduled_at) WHERE ((return_scheduled_at IS NOT NULL) AND ((status)::text = 'ACTIVE'::text));


--
-- TOC entry 6267 (class 1259 OID 33249)
-- Name: idx_vibans_status; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_vibans_status ON public.vibans USING btree (status);


--
-- TOC entry 6268 (class 1259 OID 33250)
-- Name: idx_vibans_type; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_vibans_type ON public.vibans USING btree (viban_type);


--
-- TOC entry 6269 (class 1259 OID 33243)
-- Name: idx_vibans_viban; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE UNIQUE INDEX idx_vibans_viban ON public.vibans USING btree (viban);


--
-- TOC entry 6270 (class 1259 OID 33244)
-- Name: idx_vibans_virtual_account_id; Type: INDEX; Schema: public; Owner: vam_user
--

CREATE INDEX idx_vibans_virtual_account_id ON public.vibans USING btree (virtual_account_id);


--
-- TOC entry 6682 (class 2620 OID 51060)
-- Name: fx_rates trg_fx_rates_updated_at; Type: TRIGGER; Schema: public; Owner: postgres
--

CREATE TRIGGER trg_fx_rates_updated_at BEFORE UPDATE ON public.fx_rates FOR EACH ROW EXECUTE FUNCTION public.update_fx_rates_updated_at();


--
-- TOC entry 6673 (class 2620 OID 33146)
-- Name: hierarchy_level_configs trg_hierarchy_level_configs_timestamp; Type: TRIGGER; Schema: public; Owner: vam_user
--

CREATE TRIGGER trg_hierarchy_level_configs_timestamp BEFORE UPDATE ON public.hierarchy_level_configs FOR EACH ROW EXECUTE FUNCTION public.update_hierarchy_timestamp();


--
-- TOC entry 6674 (class 2620 OID 33149)
-- Name: hierarchy_nodes trg_hierarchy_nodes_child_count; Type: TRIGGER; Schema: public; Owner: vam_user
--

CREATE TRIGGER trg_hierarchy_nodes_child_count AFTER INSERT OR DELETE ON public.hierarchy_nodes FOR EACH ROW EXECUTE FUNCTION public.update_parent_child_count();


--
-- TOC entry 6675 (class 2620 OID 33147)
-- Name: hierarchy_nodes trg_hierarchy_nodes_timestamp; Type: TRIGGER; Schema: public; Owner: vam_user
--

CREATE TRIGGER trg_hierarchy_nodes_timestamp BEFORE UPDATE ON public.hierarchy_nodes FOR EACH ROW EXECUTE FUNCTION public.update_hierarchy_timestamp();


--
-- TOC entry 6672 (class 2620 OID 51133)
-- Name: ihb_deposits trg_ihb_deposit_position_update; Type: TRIGGER; Schema: public; Owner: vam_user
--

CREATE TRIGGER trg_ihb_deposit_position_update AFTER INSERT OR DELETE OR UPDATE ON public.ihb_deposits FOR EACH ROW EXECUTE FUNCTION public.trg_update_ihb_position_on_deposit_change();


--
-- TOC entry 6671 (class 2620 OID 51131)
-- Name: ihb_loans trg_ihb_loan_position_update; Type: TRIGGER; Schema: public; Owner: vam_user
--

CREATE TRIGGER trg_ihb_loan_position_update AFTER INSERT OR DELETE OR UPDATE ON public.ihb_loans FOR EACH ROW EXECUTE FUNCTION public.trg_update_ihb_position_on_loan_change();


--
-- TOC entry 6681 (class 2620 OID 42325)
-- Name: program_charge_overrides trg_pco_updated_at; Type: TRIGGER; Schema: public; Owner: vam_user
--

CREATE TRIGGER trg_pco_updated_at BEFORE UPDATE ON public.program_charge_overrides FOR EACH ROW EXECUTE FUNCTION public.update_pco_updated_at();


--
-- TOC entry 6680 (class 2620 OID 33587)
-- Name: receivables trg_receivable_outstanding; Type: TRIGGER; Schema: public; Owner: vam_user
--

CREATE TRIGGER trg_receivable_outstanding BEFORE INSERT OR UPDATE ON public.receivables FOR EACH ROW EXECUTE FUNCTION public.update_receivable_outstanding();


--
-- TOC entry 6677 (class 2620 OID 42329)
-- Name: vibans trg_viban_pool_count; Type: TRIGGER; Schema: public; Owner: vam_user
--

CREATE TRIGGER trg_viban_pool_count AFTER INSERT OR UPDATE ON public.vibans FOR EACH ROW EXECUTE FUNCTION public.update_pool_available_count();


--
-- TOC entry 6676 (class 2620 OID 33321)
-- Name: viban_pools trg_viban_pools_timestamp; Type: TRIGGER; Schema: public; Owner: vam_user
--

CREATE TRIGGER trg_viban_pools_timestamp BEFORE UPDATE ON public.viban_pools FOR EACH ROW EXECUTE FUNCTION public.update_hierarchy_timestamp();


--
-- TOC entry 6679 (class 2620 OID 33324)
-- Name: viban_usage_log trg_viban_usage_stats; Type: TRIGGER; Schema: public; Owner: vam_user
--

CREATE TRIGGER trg_viban_usage_stats AFTER INSERT ON public.viban_usage_log FOR EACH ROW EXECUTE FUNCTION public.update_viban_usage_stats();


--
-- TOC entry 6678 (class 2620 OID 33322)
-- Name: vibans trg_vibans_timestamp; Type: TRIGGER; Schema: public; Owner: vam_user
--

CREATE TRIGGER trg_vibans_timestamp BEFORE UPDATE ON public.vibans FOR EACH ROW EXECUTE FUNCTION public.update_hierarchy_timestamp();


--
-- TOC entry 6663 (class 2606 OID 50569)
-- Name: account_attachments account_attachments_legal_entity_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.account_attachments
    ADD CONSTRAINT account_attachments_legal_entity_id_fkey FOREIGN KEY (legal_entity_id) REFERENCES public.legal_entities(id) ON DELETE CASCADE;


--
-- TOC entry 6665 (class 2606 OID 50724)
-- Name: credit_facilities credit_facilities_credit_agreement_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.credit_facilities
    ADD CONSTRAINT credit_facilities_credit_agreement_id_fkey FOREIGN KEY (credit_agreement_id) REFERENCES public.credit_agreements(id);


--
-- TOC entry 6666 (class 2606 OID 50761)
-- Name: credit_limits credit_limits_credit_facility_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.credit_limits
    ADD CONSTRAINT credit_limits_credit_facility_id_fkey FOREIGN KEY (credit_facility_id) REFERENCES public.credit_facilities(id);


--
-- TOC entry 6667 (class 2606 OID 50874)
-- Name: credit_limits credit_limits_parent_limit_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.credit_limits
    ADD CONSTRAINT credit_limits_parent_limit_id_fkey FOREIGN KEY (parent_limit_id) REFERENCES public.credit_limits(id);


--
-- TOC entry 6654 (class 2606 OID 34145)
-- Name: exception_transactions exception_transactions_exception_va_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.exception_transactions
    ADD CONSTRAINT exception_transactions_exception_va_id_fkey FOREIGN KEY (exception_va_id) REFERENCES public.virtual_accounts(id);


--
-- TOC entry 6655 (class 2606 OID 34135)
-- Name: exception_transactions exception_transactions_original_transaction_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.exception_transactions
    ADD CONSTRAINT exception_transactions_original_transaction_id_fkey FOREIGN KEY (original_transaction_id) REFERENCES public.va_movements(id);


--
-- TOC entry 6656 (class 2606 OID 34140)
-- Name: exception_transactions exception_transactions_original_va_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.exception_transactions
    ADD CONSTRAINT exception_transactions_original_va_id_fkey FOREIGN KEY (original_va_id) REFERENCES public.virtual_accounts(id);


--
-- TOC entry 6657 (class 2606 OID 34150)
-- Name: exception_transactions exception_transactions_target_va_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.exception_transactions
    ADD CONSTRAINT exception_transactions_target_va_id_fkey FOREIGN KEY (target_va_id) REFERENCES public.virtual_accounts(id);


--
-- TOC entry 6611 (class 2606 OID 32779)
-- Name: ihb_interest_accruals fk82hnudwjc8avnmo3uy7eagfra; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.ihb_interest_accruals
    ADD CONSTRAINT fk82hnudwjc8avnmo3uy7eagfra FOREIGN KEY (entity_id) REFERENCES public.ihb_entities(id);


--
-- TOC entry 6612 (class 2606 OID 32848)
-- Name: party_bank_accounts fk8wg26nbf5esaqqxll5gjh6fty; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.party_bank_accounts
    ADD CONSTRAINT fk8wg26nbf5esaqqxll5gjh6fty FOREIGN KEY (party_id) REFERENCES public.parties(id);


--
-- TOC entry 6602 (class 2606 OID 34225)
-- Name: ihb_transactions fk9dqqebf3rneyavereiwvmy0r8; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.ihb_transactions
    ADD CONSTRAINT fk9dqqebf3rneyavereiwvmy0r8 FOREIGN KEY (to_entity_id) REFERENCES public.ihb_entities(id);


--
-- TOC entry 6613 (class 2606 OID 32853)
-- Name: party_documents fk9gevko8g33wx2dmi2xihqxcxa; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.party_documents
    ADD CONSTRAINT fk9gevko8g33wx2dmi2xihqxcxa FOREIGN KEY (party_id) REFERENCES public.parties(id);


--
-- TOC entry 6664 (class 2606 OID 50585)
-- Name: account_attachments fk_aa_virtual_account; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.account_attachments
    ADD CONSTRAINT fk_aa_virtual_account FOREIGN KEY (virtual_account_id) REFERENCES public.virtual_accounts(id) ON DELETE CASCADE;


--
-- TOC entry 6618 (class 2606 OID 33137)
-- Name: hierarchy_node_balance_history fk_balance_history_node; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.hierarchy_node_balance_history
    ADD CONSTRAINT fk_balance_history_node FOREIGN KEY (node_id) REFERENCES public.hierarchy_nodes(id) ON DELETE CASCADE;


--
-- TOC entry 6648 (class 2606 OID 33682)
-- Name: payment_batches fk_batch_corporate; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.payment_batches
    ADD CONSTRAINT fk_batch_corporate FOREIGN KEY (corporate_id) REFERENCES public.corporates(id);


--
-- TOC entry 6653 (class 2606 OID 33948)
-- Name: calculated_charges fk_calc_charge_config; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.calculated_charges
    ADD CONSTRAINT fk_calc_charge_config FOREIGN KEY (charge_config_id) REFERENCES public.charge_configurations(id);


--
-- TOC entry 6652 (class 2606 OID 33918)
-- Name: calculated_taxes fk_calc_tax_config; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.calculated_taxes
    ADD CONSTRAINT fk_calc_tax_config FOREIGN KEY (tax_config_id) REFERENCES public.tax_configurations(id);


--
-- TOC entry 6668 (class 2606 OID 50887)
-- Name: credit_limits fk_cl_parent_limit; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.credit_limits
    ADD CONSTRAINT fk_cl_parent_limit FOREIGN KEY (parent_limit_id) REFERENCES public.credit_limits(id) ON DELETE SET NULL;


--
-- TOC entry 6649 (class 2606 OID 33713)
-- Name: payment_executions fk_exec_batch; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.payment_executions
    ADD CONSTRAINT fk_exec_batch FOREIGN KEY (batch_id) REFERENCES public.payment_batches(id);


--
-- TOC entry 6598 (class 2606 OID 51158)
-- Name: ihb_deposits fk_ihb_deposit_depositor_entity; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.ihb_deposits
    ADD CONSTRAINT fk_ihb_deposit_depositor_entity FOREIGN KEY (depositor_legal_entity_id) REFERENCES public.legal_entities(id) ON DELETE SET NULL;


--
-- TOC entry 6599 (class 2606 OID 51150)
-- Name: ihb_deposits fk_ihb_deposit_sweep_rule; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.ihb_deposits
    ADD CONSTRAINT fk_ihb_deposit_sweep_rule FOREIGN KEY (sweep_rule_id) REFERENCES public.sweep_rules(id) ON DELETE SET NULL;


--
-- TOC entry 6600 (class 2606 OID 51163)
-- Name: ihb_deposits fk_ihb_deposit_treasury_entity; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.ihb_deposits
    ADD CONSTRAINT fk_ihb_deposit_treasury_entity FOREIGN KEY (treasury_legal_entity_id) REFERENCES public.legal_entities(id) ON DELETE SET NULL;


--
-- TOC entry 6660 (class 2606 OID 50590)
-- Name: legal_entities fk_le_corporate; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.legal_entities
    ADD CONSTRAINT fk_le_corporate FOREIGN KEY (corporate_id) REFERENCES public.corporates(id) ON DELETE CASCADE;


--
-- TOC entry 6661 (class 2606 OID 51135)
-- Name: legal_entities fk_legal_entity_ihb_interest_config; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.legal_entities
    ADD CONSTRAINT fk_legal_entity_ihb_interest_config FOREIGN KEY (ihb_interest_config_id) REFERENCES public.interest_configurations(id) ON DELETE SET NULL;


--
-- TOC entry 6614 (class 2606 OID 33063)
-- Name: hierarchy_level_configs fk_level_config_program; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.hierarchy_level_configs
    ADD CONSTRAINT fk_level_config_program FOREIGN KEY (program_id) REFERENCES public.unified_programs(id) ON DELETE CASCADE;


--
-- TOC entry 6642 (class 2606 OID 33529)
-- Name: receivable_line_items fk_line_receivable; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.receivable_line_items
    ADD CONSTRAINT fk_line_receivable FOREIGN KEY (receivable_id) REFERENCES public.receivables(id) ON DELETE CASCADE;


--
-- TOC entry 6615 (class 2606 OID 33106)
-- Name: hierarchy_nodes fk_node_parent; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.hierarchy_nodes
    ADD CONSTRAINT fk_node_parent FOREIGN KEY (parent_id) REFERENCES public.hierarchy_nodes(id) ON DELETE CASCADE;


--
-- TOC entry 6616 (class 2606 OID 33101)
-- Name: hierarchy_nodes fk_node_program; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.hierarchy_nodes
    ADD CONSTRAINT fk_node_program FOREIGN KEY (program_id) REFERENCES public.unified_programs(id) ON DELETE CASCADE;


--
-- TOC entry 6617 (class 2606 OID 33111)
-- Name: hierarchy_nodes fk_node_virtual_account; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.hierarchy_nodes
    ADD CONSTRAINT fk_node_virtual_account FOREIGN KEY (virtual_account_id) REFERENCES public.virtual_accounts(id) ON DELETE SET NULL;


--
-- TOC entry 6650 (class 2606 OID 33741)
-- Name: notifications fk_notif_corporate; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.notifications
    ADD CONSTRAINT fk_notif_corporate FOREIGN KEY (corporate_id) REFERENCES public.corporates(id);


--
-- TOC entry 6575 (class 2606 OID 50803)
-- Name: physical_accounts fk_pa_credit_facility; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.physical_accounts
    ADD CONSTRAINT fk_pa_credit_facility FOREIGN KEY (credit_facility_id) REFERENCES public.credit_facilities(id) ON DELETE SET NULL;


--
-- TOC entry 6639 (class 2606 OID 33491)
-- Name: receivable_payments fk_payment_receivable; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.receivable_payments
    ADD CONSTRAINT fk_payment_receivable FOREIGN KEY (receivable_id) REFERENCES public.receivables(id);


--
-- TOC entry 6640 (class 2606 OID 33496)
-- Name: receivable_payments fk_payment_transaction; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.receivable_payments
    ADD CONSTRAINT fk_payment_transaction FOREIGN KEY (transaction_id) REFERENCES public.va_movements(id);


--
-- TOC entry 6641 (class 2606 OID 33501)
-- Name: receivable_payments fk_payment_viban; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.receivable_payments
    ADD CONSTRAINT fk_payment_viban FOREIGN KEY (viban_id) REFERENCES public.vibans(id);


--
-- TOC entry 6619 (class 2606 OID 33188)
-- Name: viban_pools fk_pool_program; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.viban_pools
    ADD CONSTRAINT fk_pool_program FOREIGN KEY (program_id) REFERENCES public.unified_programs(id) ON DELETE CASCADE;


--
-- TOC entry 6576 (class 2606 OID 33377)
-- Name: unified_programs fk_program_root_node; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.unified_programs
    ADD CONSTRAINT fk_program_root_node FOREIGN KEY (root_hierarchy_node_id) REFERENCES public.hierarchy_nodes(id) ON DELETE SET NULL;


--
-- TOC entry 6577 (class 2606 OID 33382)
-- Name: unified_programs fk_program_viban_pool; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.unified_programs
    ADD CONSTRAINT fk_program_viban_pool FOREIGN KEY (default_viban_pool_id) REFERENCES public.viban_pools(id) ON DELETE SET NULL;


--
-- TOC entry 6628 (class 2606 OID 51623)
-- Name: receivables fk_receivable_cobo_collector; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.receivables
    ADD CONSTRAINT fk_receivable_cobo_collector FOREIGN KEY (cobo_collector_entity_id) REFERENCES public.legal_entities(id) ON DELETE SET NULL;


--
-- TOC entry 6629 (class 2606 OID 33430)
-- Name: receivables fk_receivable_corporate; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.receivables
    ADD CONSTRAINT fk_receivable_corporate FOREIGN KEY (corporate_id) REFERENCES public.corporates(id);


--
-- TOC entry 6630 (class 2606 OID 51633)
-- Name: receivables fk_receivable_counterparty_payable; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.receivables
    ADD CONSTRAINT fk_receivable_counterparty_payable FOREIGN KEY (counterparty_payable_id) REFERENCES public.payables(id) ON DELETE SET NULL;


--
-- TOC entry 6631 (class 2606 OID 51613)
-- Name: receivables fk_receivable_customer_party; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.receivables
    ADD CONSTRAINT fk_receivable_customer_party FOREIGN KEY (customer_party_id) REFERENCES public.parties(id) ON DELETE SET NULL;


--
-- TOC entry 6632 (class 2606 OID 33450)
-- Name: receivables fk_receivable_hierarchy; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.receivables
    ADD CONSTRAINT fk_receivable_hierarchy FOREIGN KEY (hierarchy_node_id) REFERENCES public.hierarchy_nodes(id);


--
-- TOC entry 6633 (class 2606 OID 51618)
-- Name: receivables fk_receivable_intercompany_entity; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.receivables
    ADD CONSTRAINT fk_receivable_intercompany_entity FOREIGN KEY (intercompany_entity_id) REFERENCES public.legal_entities(id) ON DELETE SET NULL;


--
-- TOC entry 6634 (class 2606 OID 51628)
-- Name: receivables fk_receivable_netting_cycle; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.receivables
    ADD CONSTRAINT fk_receivable_netting_cycle FOREIGN KEY (netting_cycle_id) REFERENCES public.netting_cycles(id) ON DELETE SET NULL;


--
-- TOC entry 6635 (class 2606 OID 51608)
-- Name: receivables fk_receivable_owning_entity; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.receivables
    ADD CONSTRAINT fk_receivable_owning_entity FOREIGN KEY (owning_entity_id) REFERENCES public.legal_entities(id) ON DELETE SET NULL;


--
-- TOC entry 6636 (class 2606 OID 33435)
-- Name: receivables fk_receivable_program; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.receivables
    ADD CONSTRAINT fk_receivable_program FOREIGN KEY (program_id) REFERENCES public.unified_programs(id);


--
-- TOC entry 6637 (class 2606 OID 33440)
-- Name: receivables fk_receivable_va; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.receivables
    ADD CONSTRAINT fk_receivable_va FOREIGN KEY (virtual_account_id) REFERENCES public.virtual_accounts(id);


--
-- TOC entry 6638 (class 2606 OID 33445)
-- Name: receivables fk_receivable_viban; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.receivables
    ADD CONSTRAINT fk_receivable_viban FOREIGN KEY (primary_viban_id) REFERENCES public.vibans(id);


--
-- TOC entry 6627 (class 2606 OID 33315)
-- Name: viban_sequences fk_sequence_pool; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.viban_sequences
    ADD CONSTRAINT fk_sequence_pool FOREIGN KEY (pool_id) REFERENCES public.viban_pools(id) ON DELETE CASCADE;


--
-- TOC entry 6589 (class 2606 OID 51142)
-- Name: sweep_executions fk_sweep_exec_ihb_deposit; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.sweep_executions
    ADD CONSTRAINT fk_sweep_exec_ihb_deposit FOREIGN KEY (ihb_deposit_id) REFERENCES public.ihb_deposits(id) ON DELETE SET NULL;


--
-- TOC entry 6651 (class 2606 OID 33854)
-- Name: tax_configurations fk_tax_jurisdiction; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.tax_configurations
    ADD CONSTRAINT fk_tax_jurisdiction FOREIGN KEY (jurisdiction_id) REFERENCES public.tax_jurisdictions(id);


--
-- TOC entry 6578 (class 2606 OID 33387)
-- Name: va_movements fk_txn_viban; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.va_movements
    ADD CONSTRAINT fk_txn_viban FOREIGN KEY (viban_id) REFERENCES public.vibans(id) ON DELETE SET NULL;


--
-- TOC entry 6643 (class 2606 OID 34211)
-- Name: unmatched_payments fk_unmatched_exception; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.unmatched_payments
    ADD CONSTRAINT fk_unmatched_exception FOREIGN KEY (exception_transaction_id) REFERENCES public.exception_transactions(id);


--
-- TOC entry 6644 (class 2606 OID 33567)
-- Name: unmatched_payments fk_unmatched_matched_recv; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.unmatched_payments
    ADD CONSTRAINT fk_unmatched_matched_recv FOREIGN KEY (matched_receivable_id) REFERENCES public.receivables(id);


--
-- TOC entry 6645 (class 2606 OID 33552)
-- Name: unmatched_payments fk_unmatched_transaction; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.unmatched_payments
    ADD CONSTRAINT fk_unmatched_transaction FOREIGN KEY (transaction_id) REFERENCES public.va_movements(id);


--
-- TOC entry 6646 (class 2606 OID 33562)
-- Name: unmatched_payments fk_unmatched_va; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.unmatched_payments
    ADD CONSTRAINT fk_unmatched_va FOREIGN KEY (virtual_account_id) REFERENCES public.virtual_accounts(id);


--
-- TOC entry 6647 (class 2606 OID 33557)
-- Name: unmatched_payments fk_unmatched_viban; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.unmatched_payments
    ADD CONSTRAINT fk_unmatched_viban FOREIGN KEY (viban_id) REFERENCES public.vibans(id);


--
-- TOC entry 6624 (class 2606 OID 33287)
-- Name: viban_usage_log fk_usage_program; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.viban_usage_log
    ADD CONSTRAINT fk_usage_program FOREIGN KEY (program_id) REFERENCES public.unified_programs(id) ON DELETE CASCADE;


--
-- TOC entry 6625 (class 2606 OID 33277)
-- Name: viban_usage_log fk_usage_viban; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.viban_usage_log
    ADD CONSTRAINT fk_usage_viban FOREIGN KEY (viban_id) REFERENCES public.vibans(id) ON DELETE CASCADE;


--
-- TOC entry 6626 (class 2606 OID 33282)
-- Name: viban_usage_log fk_usage_virtual_account; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.viban_usage_log
    ADD CONSTRAINT fk_usage_virtual_account FOREIGN KEY (virtual_account_id) REFERENCES public.virtual_accounts(id) ON DELETE CASCADE;


--
-- TOC entry 6579 (class 2606 OID 50818)
-- Name: virtual_accounts fk_va_external_interest; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.virtual_accounts
    ADD CONSTRAINT fk_va_external_interest FOREIGN KEY (external_interest_config_id) REFERENCES public.interest_configurations(id) ON DELETE SET NULL;


--
-- TOC entry 6580 (class 2606 OID 50808)
-- Name: virtual_accounts fk_va_external_limit; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.virtual_accounts
    ADD CONSTRAINT fk_va_external_limit FOREIGN KEY (external_limit_id) REFERENCES public.credit_limits(id) ON DELETE SET NULL;


--
-- TOC entry 6581 (class 2606 OID 33367)
-- Name: virtual_accounts fk_va_hierarchy_node; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.virtual_accounts
    ADD CONSTRAINT fk_va_hierarchy_node FOREIGN KEY (hierarchy_node_id) REFERENCES public.hierarchy_nodes(id) ON DELETE SET NULL;


--
-- TOC entry 6582 (class 2606 OID 50823)
-- Name: virtual_accounts fk_va_internal_interest; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.virtual_accounts
    ADD CONSTRAINT fk_va_internal_interest FOREIGN KEY (internal_interest_config_id) REFERENCES public.interest_configurations(id) ON DELETE SET NULL;


--
-- TOC entry 6583 (class 2606 OID 50813)
-- Name: virtual_accounts fk_va_internal_limit; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.virtual_accounts
    ADD CONSTRAINT fk_va_internal_limit FOREIGN KEY (internal_limit_id) REFERENCES public.credit_limits(id) ON DELETE SET NULL;


--
-- TOC entry 6584 (class 2606 OID 50492)
-- Name: virtual_accounts fk_va_linked_physical; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.virtual_accounts
    ADD CONSTRAINT fk_va_linked_physical FOREIGN KEY (linked_physical_account_id) REFERENCES public.physical_accounts(id) ON DELETE SET NULL;


--
-- TOC entry 6585 (class 2606 OID 50580)
-- Name: virtual_accounts fk_va_owning_entity; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.virtual_accounts
    ADD CONSTRAINT fk_va_owning_entity FOREIGN KEY (owning_entity_id) REFERENCES public.legal_entities(id) ON DELETE SET NULL;


--
-- TOC entry 6586 (class 2606 OID 50487)
-- Name: virtual_accounts fk_va_parent_account; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.virtual_accounts
    ADD CONSTRAINT fk_va_parent_account FOREIGN KEY (parent_account_id) REFERENCES public.virtual_accounts(id) ON DELETE SET NULL;


--
-- TOC entry 6587 (class 2606 OID 33372)
-- Name: virtual_accounts fk_va_primary_viban; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.virtual_accounts
    ADD CONSTRAINT fk_va_primary_viban FOREIGN KEY (primary_viban_id) REFERENCES public.vibans(id) ON DELETE SET NULL;


--
-- TOC entry 6620 (class 2606 OID 33238)
-- Name: vibans fk_viban_hierarchy_node; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.vibans
    ADD CONSTRAINT fk_viban_hierarchy_node FOREIGN KEY (hierarchy_node_id) REFERENCES public.hierarchy_nodes(id) ON DELETE SET NULL;


--
-- TOC entry 6621 (class 2606 OID 33233)
-- Name: vibans fk_viban_pool; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.vibans
    ADD CONSTRAINT fk_viban_pool FOREIGN KEY (pool_id) REFERENCES public.viban_pools(id) ON DELETE SET NULL;


--
-- TOC entry 6622 (class 2606 OID 33228)
-- Name: vibans fk_viban_program; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.vibans
    ADD CONSTRAINT fk_viban_program FOREIGN KEY (program_id) REFERENCES public.unified_programs(id) ON DELETE CASCADE;


--
-- TOC entry 6623 (class 2606 OID 33223)
-- Name: vibans fk_viban_virtual_account; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.vibans
    ADD CONSTRAINT fk_viban_virtual_account FOREIGN KEY (virtual_account_id) REFERENCES public.virtual_accounts(id) ON DELETE CASCADE;


--
-- TOC entry 6603 (class 2606 OID 34220)
-- Name: ihb_transactions fksyeawewnf9k7oodp3rsin8c2c; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.ihb_transactions
    ADD CONSTRAINT fksyeawewnf9k7oodp3rsin8c2c FOREIGN KEY (from_entity_id) REFERENCES public.ihb_entities(id);


--
-- TOC entry 6669 (class 2606 OID 51034)
-- Name: fx_rates fx_rates_corporate_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.fx_rates
    ADD CONSTRAINT fx_rates_corporate_id_fkey FOREIGN KEY (corporate_id) REFERENCES public.corporates(id);


--
-- TOC entry 6670 (class 2606 OID 51039)
-- Name: fx_rates fx_rates_program_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.fx_rates
    ADD CONSTRAINT fx_rates_program_id_fkey FOREIGN KEY (program_id) REFERENCES public.unified_programs(id);


--
-- TOC entry 6595 (class 2606 OID 31034)
-- Name: ihb_accounts ihb_accounts_entity_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.ihb_accounts
    ADD CONSTRAINT ihb_accounts_entity_id_fkey FOREIGN KEY (entity_id) REFERENCES public.ihb_entities(id);


--
-- TOC entry 6601 (class 2606 OID 31105)
-- Name: ihb_deposits ihb_deposits_depositor_entity_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.ihb_deposits
    ADD CONSTRAINT ihb_deposits_depositor_entity_id_fkey FOREIGN KEY (depositor_entity_id) REFERENCES public.ihb_entities(id);


--
-- TOC entry 6596 (class 2606 OID 31073)
-- Name: ihb_loans ihb_loans_borrower_entity_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.ihb_loans
    ADD CONSTRAINT ihb_loans_borrower_entity_id_fkey FOREIGN KEY (borrower_entity_id) REFERENCES public.ihb_entities(id);


--
-- TOC entry 6597 (class 2606 OID 31068)
-- Name: ihb_loans ihb_loans_lender_entity_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.ihb_loans
    ADD CONSTRAINT ihb_loans_lender_entity_id_fkey FOREIGN KEY (lender_entity_id) REFERENCES public.ihb_entities(id);


--
-- TOC entry 6604 (class 2606 OID 31138)
-- Name: ihb_transactions ihb_transactions_credit_entity_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.ihb_transactions
    ADD CONSTRAINT ihb_transactions_credit_entity_id_fkey FOREIGN KEY (credit_entity_id) REFERENCES public.ihb_entities(id);


--
-- TOC entry 6605 (class 2606 OID 31133)
-- Name: ihb_transactions ihb_transactions_debit_entity_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.ihb_transactions
    ADD CONSTRAINT ihb_transactions_debit_entity_id_fkey FOREIGN KEY (debit_entity_id) REFERENCES public.ihb_entities(id);


--
-- TOC entry 6606 (class 2606 OID 31182)
-- Name: integration_connections integration_connections_connector_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.integration_connections
    ADD CONSTRAINT integration_connections_connector_id_fkey FOREIGN KEY (connector_id) REFERENCES public.integration_connectors(id);


--
-- TOC entry 6607 (class 2606 OID 31206)
-- Name: integration_data_flows integration_data_flows_connection_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.integration_data_flows
    ADD CONSTRAINT integration_data_flows_connection_id_fkey FOREIGN KEY (connection_id) REFERENCES public.integration_connections(id) ON DELETE CASCADE;


--
-- TOC entry 6608 (class 2606 OID 31229)
-- Name: integration_field_mappings integration_field_mappings_flow_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.integration_field_mappings
    ADD CONSTRAINT integration_field_mappings_flow_id_fkey FOREIGN KEY (flow_id) REFERENCES public.integration_data_flows(id) ON DELETE CASCADE;


--
-- TOC entry 6609 (class 2606 OID 31250)
-- Name: integration_sync_logs integration_sync_logs_connection_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.integration_sync_logs
    ADD CONSTRAINT integration_sync_logs_connection_id_fkey FOREIGN KEY (connection_id) REFERENCES public.integration_connections(id);


--
-- TOC entry 6610 (class 2606 OID 31255)
-- Name: integration_sync_logs integration_sync_logs_flow_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.integration_sync_logs
    ADD CONSTRAINT integration_sync_logs_flow_id_fkey FOREIGN KEY (flow_id) REFERENCES public.integration_data_flows(id);


--
-- TOC entry 6662 (class 2606 OID 50531)
-- Name: legal_entities legal_entities_parent_entity_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.legal_entities
    ADD CONSTRAINT legal_entities_parent_entity_id_fkey FOREIGN KEY (parent_entity_id) REFERENCES public.legal_entities(id) ON DELETE SET NULL;


--
-- TOC entry 6593 (class 2606 OID 30944)
-- Name: netting_entries netting_entries_cycle_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.netting_entries
    ADD CONSTRAINT netting_entries_cycle_id_fkey FOREIGN KEY (cycle_id) REFERENCES public.netting_cycles(id) ON DELETE CASCADE;


--
-- TOC entry 6594 (class 2606 OID 30968)
-- Name: netting_settlements netting_settlements_cycle_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.netting_settlements
    ADD CONSTRAINT netting_settlements_cycle_id_fkey FOREIGN KEY (cycle_id) REFERENCES public.netting_cycles(id) ON DELETE CASCADE;


--
-- TOC entry 6592 (class 2606 OID 30891)
-- Name: pool_interest_calculations pool_interest_calculations_pool_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.pool_interest_calculations
    ADD CONSTRAINT pool_interest_calculations_pool_id_fkey FOREIGN KEY (pool_id) REFERENCES public.notional_pools(id);


--
-- TOC entry 6591 (class 2606 OID 30868)
-- Name: pool_members pool_members_pool_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.pool_members
    ADD CONSTRAINT pool_members_pool_id_fkey FOREIGN KEY (pool_id) REFERENCES public.notional_pools(id) ON DELETE CASCADE;


--
-- TOC entry 6658 (class 2606 OID 42309)
-- Name: program_charge_overrides program_charge_overrides_charge_config_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.program_charge_overrides
    ADD CONSTRAINT program_charge_overrides_charge_config_id_fkey FOREIGN KEY (charge_config_id) REFERENCES public.charge_configurations(id);


--
-- TOC entry 6659 (class 2606 OID 42304)
-- Name: program_charge_overrides program_charge_overrides_program_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.program_charge_overrides
    ADD CONSTRAINT program_charge_overrides_program_id_fkey FOREIGN KEY (program_id) REFERENCES public.unified_programs(id);


--
-- TOC entry 6590 (class 2606 OID 30818)
-- Name: sweep_executions sweep_executions_rule_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.sweep_executions
    ADD CONSTRAINT sweep_executions_rule_id_fkey FOREIGN KEY (rule_id) REFERENCES public.sweep_rules(id);


--
-- TOC entry 6588 (class 2606 OID 30790)
-- Name: sweep_rule_sources sweep_rule_sources_rule_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: vam_user
--

ALTER TABLE ONLY public.sweep_rule_sources
    ADD CONSTRAINT sweep_rule_sources_rule_id_fkey FOREIGN KEY (rule_id) REFERENCES public.sweep_rules(id) ON DELETE CASCADE;


--
-- TOC entry 6850 (class 0 OID 0)
-- Dependencies: 8
-- Name: SCHEMA public; Type: ACL; Schema: -; Owner: pg_database_owner
--

GRANT ALL ON SCHEMA public TO vam_user;


--
-- TOC entry 6854 (class 0 OID 0)
-- Dependencies: 388
-- Name: FUNCTION gtrgm_in(cstring); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.gtrgm_in(cstring) TO vam_user;


--
-- TOC entry 6855 (class 0 OID 0)
-- Dependencies: 365
-- Name: FUNCTION gtrgm_out(public.gtrgm); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.gtrgm_out(public.gtrgm) TO vam_user;


--
-- TOC entry 6857 (class 0 OID 0)
-- Dependencies: 341
-- Name: FUNCTION calculate_charge_amount(p_base_amount numeric, p_charge_code character varying); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.calculate_charge_amount(p_base_amount numeric, p_charge_code character varying) TO vam_user;


--
-- TOC entry 6858 (class 0 OID 0)
-- Dependencies: 340
-- Name: FUNCTION calculate_node_balance(p_node_id uuid); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.calculate_node_balance(p_node_id uuid) TO vam_user;


--
-- TOC entry 6859 (class 0 OID 0)
-- Dependencies: 436
-- Name: FUNCTION calculate_payable_amounts(); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.calculate_payable_amounts() TO vam_user;


--
-- TOC entry 6861 (class 0 OID 0)
-- Dependencies: 410
-- Name: FUNCTION calculate_program_charge(p_program_id uuid, p_charge_code character varying, p_amount numeric); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.calculate_program_charge(p_program_id uuid, p_charge_code character varying, p_amount numeric) TO vam_user;


--
-- TOC entry 6863 (class 0 OID 0)
-- Dependencies: 397
-- Name: FUNCTION calculate_tax_amount(p_base_amount numeric, p_tax_code character varying); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.calculate_tax_amount(p_base_amount numeric, p_tax_code character varying) TO vam_user;


--
-- TOC entry 6865 (class 0 OID 0)
-- Dependencies: 373
-- Name: FUNCTION can_va_debit(p_va_id uuid, p_amount numeric); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.can_va_debit(p_va_id uuid, p_amount numeric) TO vam_user;


--
-- TOC entry 6867 (class 0 OID 0)
-- Dependencies: 396
-- Name: FUNCTION convert_currency(p_amount numeric, p_from_currency character varying, p_to_currency character varying, p_rate_date date, p_rate_type character varying); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.convert_currency(p_amount numeric, p_from_currency character varying, p_to_currency character varying, p_rate_date date, p_rate_type character varying) TO vam_user;


--
-- TOC entry 6870 (class 0 OID 0)
-- Dependencies: 311
-- Name: FUNCTION enable_ihb_for_entity(p_entity_id uuid, p_credit_limit numeric, p_can_lend boolean, p_can_borrow boolean, p_lending_spread numeric, p_borrowing_spread numeric); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.enable_ihb_for_entity(p_entity_id uuid, p_credit_limit numeric, p_can_lend boolean, p_can_borrow boolean, p_lending_spread numeric, p_borrowing_spread numeric) TO vam_user;


--
-- TOC entry 6872 (class 0 OID 0)
-- Dependencies: 331
-- Name: FUNCTION fn_get_or_create_currency_mirror(p_hierarchy_node_id uuid, p_currency_code character varying, p_program_id uuid); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.fn_get_or_create_currency_mirror(p_hierarchy_node_id uuid, p_currency_code character varying, p_program_id uuid) TO vam_user;


--
-- TOC entry 6874 (class 0 OID 0)
-- Dependencies: 384
-- Name: FUNCTION fn_propagate_mirror_balance(p_va_id uuid, p_delta numeric); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.fn_propagate_mirror_balance(p_va_id uuid, p_delta numeric) TO vam_user;


--
-- TOC entry 6875 (class 0 OID 0)
-- Dependencies: 343
-- Name: FUNCTION generate_batch_reference(p_corporate_id uuid); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.generate_batch_reference(p_corporate_id uuid) TO vam_user;


--
-- TOC entry 6876 (class 0 OID 0)
-- Dependencies: 324
-- Name: FUNCTION generate_payable_number(p_type character varying, p_corporate_id uuid); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.generate_payable_number(p_type character varying, p_corporate_id uuid) TO vam_user;


--
-- TOC entry 6877 (class 0 OID 0)
-- Dependencies: 380
-- Name: FUNCTION generate_receivable_number(p_type character varying, p_corporate_id uuid); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.generate_receivable_number(p_type character varying, p_corporate_id uuid) TO vam_user;


--
-- TOC entry 6879 (class 0 OID 0)
-- Dependencies: 429
-- Name: FUNCTION generate_recharge_reference(); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.generate_recharge_reference() TO vam_user;


--
-- TOC entry 6880 (class 0 OID 0)
-- Dependencies: 408
-- Name: FUNCTION generate_viban(p_country_code character varying, p_bank_code character varying, p_account_number character varying); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.generate_viban(p_country_code character varying, p_bank_code character varying, p_account_number character varying) TO vam_user;


--
-- TOC entry 6882 (class 0 OID 0)
-- Dependencies: 370
-- Name: FUNCTION get_entity_available_limit(p_entity_id uuid); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.get_entity_available_limit(p_entity_id uuid) TO vam_user;


--
-- TOC entry 6885 (class 0 OID 0)
-- Dependencies: 416
-- Name: FUNCTION get_legal_entity_ancestors(p_entity_id uuid); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.get_legal_entity_ancestors(p_entity_id uuid) TO vam_user;


--
-- TOC entry 6887 (class 0 OID 0)
-- Dependencies: 334
-- Name: FUNCTION get_legal_entity_descendants(p_entity_id uuid); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.get_legal_entity_descendants(p_entity_id uuid) TO vam_user;


--
-- TOC entry 6888 (class 0 OID 0)
-- Dependencies: 358
-- Name: FUNCTION get_node_ancestors(p_node_id uuid); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.get_node_ancestors(p_node_id uuid) TO vam_user;


--
-- TOC entry 6889 (class 0 OID 0)
-- Dependencies: 322
-- Name: FUNCTION get_node_descendants(p_node_id uuid); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.get_node_descendants(p_node_id uuid) TO vam_user;


--
-- TOC entry 6891 (class 0 OID 0)
-- Dependencies: 316
-- Name: FUNCTION get_va_total_available(p_va_id uuid); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.get_va_total_available(p_va_id uuid) TO vam_user;


--
-- TOC entry 6892 (class 0 OID 0)
-- Dependencies: 403
-- Name: FUNCTION gin_extract_query_trgm(text, internal, smallint, internal, internal, internal, internal); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.gin_extract_query_trgm(text, internal, smallint, internal, internal, internal, internal) TO vam_user;


--
-- TOC entry 6893 (class 0 OID 0)
-- Dependencies: 339
-- Name: FUNCTION gin_extract_value_trgm(text, internal); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.gin_extract_value_trgm(text, internal) TO vam_user;


--
-- TOC entry 6894 (class 0 OID 0)
-- Dependencies: 345
-- Name: FUNCTION gin_trgm_consistent(internal, smallint, text, integer, internal, internal, internal, internal); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.gin_trgm_consistent(internal, smallint, text, integer, internal, internal, internal, internal) TO vam_user;


--
-- TOC entry 6895 (class 0 OID 0)
-- Dependencies: 359
-- Name: FUNCTION gin_trgm_triconsistent(internal, smallint, text, integer, internal, internal, internal); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.gin_trgm_triconsistent(internal, smallint, text, integer, internal, internal, internal) TO vam_user;


--
-- TOC entry 6896 (class 0 OID 0)
-- Dependencies: 328
-- Name: FUNCTION gtrgm_compress(internal); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.gtrgm_compress(internal) TO vam_user;


--
-- TOC entry 6897 (class 0 OID 0)
-- Dependencies: 348
-- Name: FUNCTION gtrgm_consistent(internal, text, smallint, oid, internal); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.gtrgm_consistent(internal, text, smallint, oid, internal) TO vam_user;


--
-- TOC entry 6898 (class 0 OID 0)
-- Dependencies: 304
-- Name: FUNCTION gtrgm_decompress(internal); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.gtrgm_decompress(internal) TO vam_user;


--
-- TOC entry 6899 (class 0 OID 0)
-- Dependencies: 354
-- Name: FUNCTION gtrgm_distance(internal, text, smallint, oid, internal); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.gtrgm_distance(internal, text, smallint, oid, internal) TO vam_user;


--
-- TOC entry 6900 (class 0 OID 0)
-- Dependencies: 421
-- Name: FUNCTION gtrgm_options(internal); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.gtrgm_options(internal) TO vam_user;


--
-- TOC entry 6901 (class 0 OID 0)
-- Dependencies: 435
-- Name: FUNCTION gtrgm_penalty(internal, internal, internal); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.gtrgm_penalty(internal, internal, internal) TO vam_user;


--
-- TOC entry 6902 (class 0 OID 0)
-- Dependencies: 320
-- Name: FUNCTION gtrgm_picksplit(internal, internal); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.gtrgm_picksplit(internal, internal) TO vam_user;


--
-- TOC entry 6903 (class 0 OID 0)
-- Dependencies: 423
-- Name: FUNCTION gtrgm_same(public.gtrgm, public.gtrgm, internal); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.gtrgm_same(public.gtrgm, public.gtrgm, internal) TO vam_user;


--
-- TOC entry 6904 (class 0 OID 0)
-- Dependencies: 390
-- Name: FUNCTION gtrgm_union(internal, internal); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.gtrgm_union(internal, internal) TO vam_user;


--
-- TOC entry 6905 (class 0 OID 0)
-- Dependencies: 428
-- Name: FUNCTION lookup_viban_for_routing(p_viban character varying); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.lookup_viban_for_routing(p_viban character varying) TO vam_user;


--
-- TOC entry 6907 (class 0 OID 0)
-- Dependencies: 434
-- Name: FUNCTION set_limit(real); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.set_limit(real) TO vam_user;


--
-- TOC entry 6908 (class 0 OID 0)
-- Dependencies: 337
-- Name: FUNCTION show_limit(); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.show_limit() TO vam_user;


--
-- TOC entry 6909 (class 0 OID 0)
-- Dependencies: 347
-- Name: FUNCTION show_trgm(text); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.show_trgm(text) TO vam_user;


--
-- TOC entry 6910 (class 0 OID 0)
-- Dependencies: 361
-- Name: FUNCTION similarity(text, text); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.similarity(text, text) TO vam_user;


--
-- TOC entry 6911 (class 0 OID 0)
-- Dependencies: 376
-- Name: FUNCTION similarity_dist(text, text); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.similarity_dist(text, text) TO vam_user;


--
-- TOC entry 6912 (class 0 OID 0)
-- Dependencies: 394
-- Name: FUNCTION similarity_op(text, text); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.similarity_op(text, text) TO vam_user;


--
-- TOC entry 6913 (class 0 OID 0)
-- Dependencies: 351
-- Name: FUNCTION strict_word_similarity(text, text); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.strict_word_similarity(text, text) TO vam_user;


--
-- TOC entry 6914 (class 0 OID 0)
-- Dependencies: 307
-- Name: FUNCTION strict_word_similarity_commutator_op(text, text); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.strict_word_similarity_commutator_op(text, text) TO vam_user;


--
-- TOC entry 6915 (class 0 OID 0)
-- Dependencies: 426
-- Name: FUNCTION strict_word_similarity_dist_commutator_op(text, text); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.strict_word_similarity_dist_commutator_op(text, text) TO vam_user;


--
-- TOC entry 6916 (class 0 OID 0)
-- Dependencies: 415
-- Name: FUNCTION strict_word_similarity_dist_op(text, text); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.strict_word_similarity_dist_op(text, text) TO vam_user;


--
-- TOC entry 6917 (class 0 OID 0)
-- Dependencies: 335
-- Name: FUNCTION strict_word_similarity_op(text, text); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.strict_word_similarity_op(text, text) TO vam_user;


--
-- TOC entry 6918 (class 0 OID 0)
-- Dependencies: 342
-- Name: FUNCTION trg_update_ihb_position_on_deposit_change(); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.trg_update_ihb_position_on_deposit_change() TO vam_user;


--
-- TOC entry 6919 (class 0 OID 0)
-- Dependencies: 407
-- Name: FUNCTION trg_update_ihb_position_on_loan_change(); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.trg_update_ihb_position_on_loan_change() TO vam_user;


--
-- TOC entry 6921 (class 0 OID 0)
-- Dependencies: 318
-- Name: FUNCTION update_entity_ihb_position(p_entity_id uuid); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.update_entity_ihb_position(p_entity_id uuid) TO vam_user;


--
-- TOC entry 6922 (class 0 OID 0)
-- Dependencies: 431
-- Name: FUNCTION update_fx_rates_updated_at(); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.update_fx_rates_updated_at() TO vam_user;


--
-- TOC entry 6923 (class 0 OID 0)
-- Dependencies: 329
-- Name: FUNCTION update_hierarchy_timestamp(); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.update_hierarchy_timestamp() TO vam_user;


--
-- TOC entry 6924 (class 0 OID 0)
-- Dependencies: 312
-- Name: FUNCTION update_parent_child_count(); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.update_parent_child_count() TO vam_user;


--
-- TOC entry 6926 (class 0 OID 0)
-- Dependencies: 398
-- Name: FUNCTION update_party_ic_exposure(p_party_id uuid, p_corporate_id uuid); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.update_party_ic_exposure(p_party_id uuid, p_corporate_id uuid) TO vam_user;


--
-- TOC entry 6927 (class 0 OID 0)
-- Dependencies: 395
-- Name: FUNCTION update_payable_outstanding(); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.update_payable_outstanding() TO vam_user;


--
-- TOC entry 6928 (class 0 OID 0)
-- Dependencies: 326
-- Name: FUNCTION update_payable_timestamp(); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.update_payable_timestamp() TO vam_user;


--
-- TOC entry 6929 (class 0 OID 0)
-- Dependencies: 317
-- Name: FUNCTION update_pco_updated_at(); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.update_pco_updated_at() TO vam_user;


--
-- TOC entry 6930 (class 0 OID 0)
-- Dependencies: 352
-- Name: FUNCTION update_pool_available_count(); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.update_pool_available_count() TO vam_user;


--
-- TOC entry 6931 (class 0 OID 0)
-- Dependencies: 375
-- Name: FUNCTION update_receivable_outstanding(); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.update_receivable_outstanding() TO vam_user;


--
-- TOC entry 6932 (class 0 OID 0)
-- Dependencies: 350
-- Name: FUNCTION update_viban_usage_stats(); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.update_viban_usage_stats() TO vam_user;


--
-- TOC entry 6934 (class 0 OID 0)
-- Dependencies: 363
-- Name: FUNCTION validate_pobo_eligibility(p_party_id uuid, p_paying_entity_id uuid, p_amount numeric); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.validate_pobo_eligibility(p_party_id uuid, p_paying_entity_id uuid, p_amount numeric) TO vam_user;


--
-- TOC entry 6935 (class 0 OID 0)
-- Dependencies: 333
-- Name: FUNCTION validate_viban(p_viban character varying); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.validate_viban(p_viban character varying) TO vam_user;


--
-- TOC entry 6936 (class 0 OID 0)
-- Dependencies: 303
-- Name: FUNCTION word_similarity(text, text); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.word_similarity(text, text) TO vam_user;


--
-- TOC entry 6937 (class 0 OID 0)
-- Dependencies: 430
-- Name: FUNCTION word_similarity_commutator_op(text, text); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.word_similarity_commutator_op(text, text) TO vam_user;


--
-- TOC entry 6938 (class 0 OID 0)
-- Dependencies: 437
-- Name: FUNCTION word_similarity_dist_commutator_op(text, text); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.word_similarity_dist_commutator_op(text, text) TO vam_user;


--
-- TOC entry 6939 (class 0 OID 0)
-- Dependencies: 336
-- Name: FUNCTION word_similarity_dist_op(text, text); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.word_similarity_dist_op(text, text) TO vam_user;


--
-- TOC entry 6940 (class 0 OID 0)
-- Dependencies: 379
-- Name: FUNCTION word_similarity_op(text, text); Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON FUNCTION public.word_similarity_op(text, text) TO vam_user;


--
-- TOC entry 6966 (class 0 OID 0)
-- Dependencies: 282
-- Name: SEQUENCE exception_number_seq; Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON SEQUENCE public.exception_number_seq TO vam_user;


--
-- TOC entry 6975 (class 0 OID 0)
-- Dependencies: 294
-- Name: TABLE fx_rates; Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON TABLE public.fx_rates TO vam_user;


--
-- TOC entry 7040 (class 0 OID 0)
-- Dependencies: 302
-- Name: TABLE payables; Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON TABLE public.payables TO vam_user;


--
-- TOC entry 7043 (class 0 OID 0)
-- Dependencies: 279
-- Name: TABLE payment_charge_summary; Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON TABLE public.payment_charge_summary TO vam_user;


--
-- TOC entry 7085 (class 0 OID 0)
-- Dependencies: 265
-- Name: TABLE receivable_aging; Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON TABLE public.receivable_aging TO vam_user;


--
-- TOC entry 7087 (class 0 OID 0)
-- Dependencies: 266
-- Name: TABLE receivable_hierarchy_summary; Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON TABLE public.receivable_hierarchy_summary TO vam_user;


--
-- TOC entry 7101 (class 0 OID 0)
-- Dependencies: 253
-- Name: TABLE v_bank_relationship_summary; Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON TABLE public.v_bank_relationship_summary TO vam_user;


--
-- TOC entry 7103 (class 0 OID 0)
-- Dependencies: 292
-- Name: TABLE v_credit_limits_summary; Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON TABLE public.v_credit_limits_summary TO vam_user;


--
-- TOC entry 7149 (class 0 OID 0)
-- Dependencies: 293
-- Name: TABLE v_currency_mirror_hierarchy; Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON TABLE public.v_currency_mirror_hierarchy TO vam_user;


--
-- TOC entry 7151 (class 0 OID 0)
-- Dependencies: 298
-- Name: TABLE v_entity_ihb_positions; Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON TABLE public.v_entity_ihb_positions TO vam_user;


--
-- TOC entry 7153 (class 0 OID 0)
-- Dependencies: 295
-- Name: TABLE v_latest_fx_rates; Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON TABLE public.v_latest_fx_rates TO vam_user;


--
-- TOC entry 7155 (class 0 OID 0)
-- Dependencies: 296
-- Name: TABLE v_fx_rate_matrix; Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON TABLE public.v_fx_rate_matrix TO vam_user;


--
-- TOC entry 7157 (class 0 OID 0)
-- Dependencies: 297
-- Name: TABLE v_ihb_entities; Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON TABLE public.v_ihb_entities TO vam_user;


--
-- TOC entry 7158 (class 0 OID 0)
-- Dependencies: 252
-- Name: TABLE v_physical_account_summary; Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON TABLE public.v_physical_account_summary TO vam_user;


--
-- TOC entry 7160 (class 0 OID 0)
-- Dependencies: 285
-- Name: TABLE v_program_effective_charges; Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON TABLE public.v_program_effective_charges TO vam_user;


--
-- TOC entry 7180 (class 0 OID 0)
-- Dependencies: 300
-- Name: TABLE vw_intercompany_parties; Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON TABLE public.vw_intercompany_parties TO vam_user;


--
-- TOC entry 7182 (class 0 OID 0)
-- Dependencies: 301
-- Name: TABLE vw_netting_eligible_parties; Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON TABLE public.vw_netting_eligible_parties TO vam_user;


--
-- TOC entry 7184 (class 0 OID 0)
-- Dependencies: 299
-- Name: TABLE vw_pobo_eligible_vendors; Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON TABLE public.vw_pobo_eligible_vendors TO vam_user;


--
-- TOC entry 2548 (class 826 OID 29772)
-- Name: DEFAULT PRIVILEGES FOR SEQUENCES; Type: DEFAULT ACL; Schema: public; Owner: postgres
--

ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public GRANT ALL ON SEQUENCES TO vam_user;


--
-- TOC entry 2549 (class 826 OID 29773)
-- Name: DEFAULT PRIVILEGES FOR FUNCTIONS; Type: DEFAULT ACL; Schema: public; Owner: postgres
--

ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public GRANT ALL ON FUNCTIONS TO vam_user;


--
-- TOC entry 2547 (class 826 OID 29771)
-- Name: DEFAULT PRIVILEGES FOR TABLES; Type: DEFAULT ACL; Schema: public; Owner: postgres
--

ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public GRANT ALL ON TABLES TO vam_user;


-- Completed on 2026-01-06 11:11:53

--
-- PostgreSQL database dump complete
--

\unrestrict hWi2gbpEFU7uv0ZGbZLDbvKIg17Ls4uoZFcYZu3ei6VZtBavSiOqdfZ9VoA3ddv

