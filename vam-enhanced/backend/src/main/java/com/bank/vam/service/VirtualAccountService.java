package com.bank.vam.service;

import com.bank.vam.dto.VirtualAccountDto;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.VirtualAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class VirtualAccountService {

    private final VirtualAccountRepository virtualAccountRepository;

    @Transactional(readOnly = true)
    public VirtualAccount getById(UUID id) {
        return virtualAccountRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Virtual account not found: " + id));
    }

    @Transactional(readOnly = true)
    public VirtualAccount getByVaNumber(String vaNumber) {
        return virtualAccountRepository.findByVaNumber(vaNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Virtual account not found: " + vaNumber));
    }

    @Transactional(readOnly = true)
    public VirtualAccount getByViban(String viban) {
        return virtualAccountRepository.findByViban(viban)
                .orElseThrow(() -> new ResourceNotFoundException("Virtual account not found for VIBAN: " + viban));
    }

    @Transactional(readOnly = true)
    public List<VirtualAccount> getByCorporateId(UUID corporateId) {
        return virtualAccountRepository.findByCorporateId(corporateId);
    }

    @Transactional(readOnly = true)
    public Page<VirtualAccount> getByCorporateId(UUID corporateId, Pageable pageable) {
        return virtualAccountRepository.findByCorporateId(corporateId, pageable);
    }

    @Transactional(readOnly = true)
    public Page<VirtualAccount> getByProgramId(UUID programId, Pageable pageable) {
        return virtualAccountRepository.findByProgramId(programId, pageable);
    }

    @Transactional(readOnly = true)
    public Page<VirtualAccount> search(String query, Pageable pageable) {
        return virtualAccountRepository.search(query, pageable);
    }

    @Transactional
    public VirtualAccount create(VirtualAccountDto.CreateRequest request) {
        log.info("Creating virtual account: {}", request.getVaName());

        // Generate VA number
        String vaNumber = generateVaNumber(request.getVaPrefix());

        VirtualAccount va = VirtualAccount.builder()
                .vaNumber(vaNumber)
                .viban(request.getViban())
                .vaName(request.getVaName())
                .programId(request.getProgramId())
                .corporateId(request.getCorporateId())
                .physicalAccountId(request.getPhysicalAccountId())
                .currencyCode(request.getCurrencyCode())
                .currentBalance(BigDecimal.ZERO)
                .availableBalance(BigDecimal.ZERO)
                .status(VirtualAccount.VaStatus.ACTIVE)
                .externalReference(request.getExternalReference())
                .metadata(request.getMetadata())
                .build();

        return virtualAccountRepository.save(va);
    }

    @Transactional
    public VirtualAccount update(UUID id, VirtualAccountDto.UpdateRequest request) {
        VirtualAccount va = getById(id);

        if (request.getVaName() != null) {
            va.setVaName(request.getVaName());
        }
        if (request.getExternalReference() != null) {
            va.setExternalReference(request.getExternalReference());
        }
        if (request.getMetadata() != null) {
            va.setMetadata(request.getMetadata());
        }

        return virtualAccountRepository.save(va);
    }

    @Transactional
    public VirtualAccount updateStatus(UUID id, VirtualAccount.VaStatus status) {
        VirtualAccount va = getById(id);
        va.setStatus(status);
        return virtualAccountRepository.save(va);
    }

    @Transactional
    public void updateBalance(UUID id, BigDecimal newBalance, BigDecimal newAvailableBalance) {
        VirtualAccount va = getById(id);
        va.setCurrentBalance(newBalance);
        va.setAvailableBalance(newAvailableBalance);
        virtualAccountRepository.save(va);
    }

    private String generateVaNumber(String prefix) {
        // Simple sequential generation - in production use a more robust approach
        long count = virtualAccountRepository.count();
        String sequence = String.format("%07d", count + 1);
        return (prefix != null ? prefix : "VA") + sequence;
    }
}
