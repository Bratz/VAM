package com.bank.vam.service.treasury;

import com.bank.vam.config.HomeBankProperties;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.treasury.ExternalMandate;
import com.bank.vam.entity.treasury.ExternalMandate.InstructionFormat;
import com.bank.vam.entity.treasury.ExternalMandate.MandateStatus;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.treasury.ExternalMandateRepository;
import com.bank.vam.service.audit.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Authorities for the home bank to instruct a corporate's account at another bank (spec external-bank-sweeps R1, R2,
 * D2, D3): registered, activated, suspended and revoked by bank operations, every change audited. The check an
 * authority must pass to be used is {@link ExternalMandate#refusal}.
 */
@Service
@RequiredArgsConstructor
public class ExternalAuthorityService {

    static final String AUDIT_ENTITY = "ExternalMandate";

    /** Allowed moves; REVOKED and EXPIRED are final. */
    private static final Map<MandateStatus, Set<MandateStatus>> NEXT = Map.of(
            MandateStatus.PENDING_ACTIVATION, Set.of(MandateStatus.ACTIVE, MandateStatus.REVOKED),
            MandateStatus.ACTIVE, Set.of(MandateStatus.SUSPENDED, MandateStatus.REVOKED),
            MandateStatus.SUSPENDED, Set.of(MandateStatus.ACTIVE, MandateStatus.REVOKED));

    private final ExternalMandateRepository mandates;
    private final VirtualAccountRepository accounts;
    private final HomeBankProperties homeBank;
    private final AuditLogService audit;

    public record Registration(UUID shadowVaId, String mandateReference, InstructionFormat instructionFormat,
                               LocalDate validFrom, LocalDate validTo, BigDecimal maxPerTransaction,
                               BigDecimal maxDaily, String documentRef) {
    }

    @Transactional(readOnly = true)
    public List<ExternalMandate> forShadow(UUID shadowVaId) {
        return mandates.findByShadowVaIdOrderByCreatedAtDesc(shadowVaId);
    }

    @Transactional(readOnly = true)
    public List<ExternalMandate> forCorporate(UUID corporateId) {
        return mandates.findByCorporateId(corporateId);
    }

    @Transactional
    public ExternalMandate register(Registration r) {
        VirtualAccount shadow = accounts.findById(r.shadowVaId())
                .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + r.shadowVaId()));
        if (!(shadow.isPhysicalMirror() || shadow.isExternalMirror()) || homeBank.matches(shadow.getBankSwift())) {
            throw new BusinessException("Account " + shadow.getVaNumber() + " is not an account at another bank: an "
                    + "authority is only for the shadow of an external bank account");
        }
        String reference = r.mandateReference() == null ? "" : r.mandateReference().trim();
        if (reference.isEmpty() || reference.length() > 60) throw new BusinessException("Authority reference is required, up to 60 characters");
        if (r.documentRef() != null && r.documentRef().length() > 500) throw new BusinessException("Document reference is longer than 500 characters");
        if (mandates.findByMandateReference(reference).isPresent()) {
            throw new BusinessException("Authority reference " + reference + " is already registered");
        }
        if (r.validFrom() != null && r.validTo() != null && r.validTo().isBefore(r.validFrom())) {
            throw new BusinessException("Valid to " + r.validTo() + " is before valid from " + r.validFrom());
        }
        positive("Limit per transaction", r.maxPerTransaction());
        positive("Daily limit", r.maxDaily());

        ExternalMandate m = mandates.save(ExternalMandate.builder()
                .mandateReference(reference)
                .shadowVaId(shadow.getId())
                .instructionFormat(r.instructionFormat() != null ? r.instructionFormat() : InstructionFormat.MT101)
                // ponytail: the closest existing type for a request for transfer; mandate_type's CHECK fixes the list
                .mandateType(ExternalMandate.MandateType.STANDING_INSTRUCTION)
                .status(MandateStatus.PENDING_ACTIVATION)
                .validFrom(r.validFrom())
                .validTo(r.validTo())
                .debitorBankBic(shadow.getBankSwift())
                .creditorBankBic(homeBank.getBic())
                .maxPerTransaction(r.maxPerTransaction())
                .maxDaily(r.maxDaily())
                .documentRef(r.documentRef())
                .build());
        record(m, shadow, "EXTERNAL_AUTHORITY_REGISTERED", "Authority " + reference + " registered ("
                + m.getInstructionFormat() + ") for " + shadow.getVaNumber(), null);
        return m;
    }

    @Transactional
    public ExternalMandate activate(UUID id) {
        return move(id, MandateStatus.ACTIVE, null);
    }

    @Transactional
    public ExternalMandate suspend(UUID id) {
        return move(id, MandateStatus.SUSPENDED, null);
    }

    @Transactional
    public ExternalMandate revoke(UUID id, String reason) {
        if (reason == null || reason.isBlank()) throw new BusinessException("A reason is required to revoke an authority");
        if (reason.trim().length() > 200) throw new BusinessException("Reason is longer than 200 characters");
        return move(id, MandateStatus.REVOKED, reason.trim());
    }

    private ExternalMandate move(UUID id, MandateStatus to, String reason) {
        ExternalMandate m = mandates.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Authority not found: " + id));
        MandateStatus from = m.getStatus();
        if (!NEXT.getOrDefault(from, Set.of()).contains(to)) {
            throw new BusinessException("Authority " + m.getMandateReference() + " is " + from + "; it cannot become " + to);
        }
        m.setStatus(to);
        if (to == MandateStatus.REVOKED) {
            m.setRevokedAt(LocalDateTime.now());
            m.setRevocationReason(reason);
        }
        m = mandates.save(m);
        record(m, accounts.findById(m.getShadowVaId()).orElse(null), "EXTERNAL_AUTHORITY_STATUS_CHANGED",
                "Authority " + m.getMandateReference() + " " + from + " → " + to + (reason != null ? ": " + reason : ""), from);
        return m;
    }

    private void record(ExternalMandate m, VirtualAccount shadow, String event, String summary, MandateStatus from) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mandateReference", m.getMandateReference());
        payload.put("shadowVaId", m.getShadowVaId());
        if (from != null) payload.put("from", from.name());
        payload.put("to", m.getStatus().name());
        if (m.getRevocationReason() != null) payload.put("reason", m.getRevocationReason());
        audit.record(event, AUDIT_ENTITY, m.getId(), shadow != null ? shadow.getCorporateId() : null, summary, payload);
    }

    private static void positive(String what, BigDecimal v) {
        if (v != null && v.signum() <= 0) throw new BusinessException(what + " must be more than zero");
    }
}
