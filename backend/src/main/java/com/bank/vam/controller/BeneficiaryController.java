package com.bank.vam.controller;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.entity.Beneficiary;
import com.bank.vam.entity.Beneficiary.BeneficiaryType;
import com.bank.vam.entity.Beneficiary.BeneficiaryStatus;
import com.bank.vam.entity.Beneficiary.ValidationStatus;
import com.bank.vam.repository.BeneficiaryRepository;
import com.bank.vam.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/beneficiaries")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class BeneficiaryController {

    private final BeneficiaryRepository beneficiaryRepository;
    private final com.bank.vam.config.MarketProfileProperties marketProfile;

    @GetMapping
    public ResponseEntity<ApiResponse<List<Beneficiary>>> getAllBeneficiaries(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) UUID corporateId) {
        
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<Beneficiary> beneficiaries;
        
        if (corporateId != null) {
            beneficiaries = beneficiaryRepository.findByCorporateId(corporateId, pageRequest);
        } else {
            beneficiaries = beneficiaryRepository.findAll(pageRequest);
        }
        
        return ResponseEntity.ok(ApiResponse.success(beneficiaries.getContent()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Beneficiary>> getBeneficiaryById(@PathVariable UUID id) {
        Beneficiary beneficiary = beneficiaryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Beneficiary not found: " + id));
        return ResponseEntity.ok(ApiResponse.success(beneficiary));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Beneficiary>> createBeneficiary(@RequestBody CreateBeneficiaryRequest request) {
        Beneficiary beneficiary = Beneficiary.builder()
                .corporateId(request.corporateId())
                .beneficiaryName(request.beneficiaryName())
                .beneficiaryType(request.beneficiaryType() != null ? 
                        BeneficiaryType.valueOf(request.beneficiaryType()) : BeneficiaryType.INDIVIDUAL)
                .bankName(request.bankName())
                .swiftCode(request.swiftCode())
                .accountNumber(request.accountNumber())
                .iban(request.iban())
                .currencyCode(request.currencyCode() != null ? request.currencyCode() : marketProfile.getDefaultCurrency())
                .countryCode(request.countryCode())
                .city(request.city())
                .addressLine1(request.addressLine1())
                .status(BeneficiaryStatus.ACTIVE)
                .validationStatus(ValidationStatus.PENDING)
                .build();
        
        Beneficiary saved = beneficiaryRepository.save(beneficiary);
        log.info("Created beneficiary: {}", saved.getBeneficiaryName());
        
        return ResponseEntity.ok(ApiResponse.success(saved));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<Beneficiary>> updateBeneficiary(
            @PathVariable UUID id,
            @RequestBody UpdateBeneficiaryRequest request) {
        
        Beneficiary beneficiary = beneficiaryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Beneficiary not found: " + id));
        
        if (request.beneficiaryName() != null) beneficiary.setBeneficiaryName(request.beneficiaryName());
        if (request.bankName() != null) beneficiary.setBankName(request.bankName());
        if (request.accountNumber() != null) beneficiary.setAccountNumber(request.accountNumber());
        if (request.iban() != null) beneficiary.setIban(request.iban());
        if (request.swiftCode() != null) beneficiary.setSwiftCode(request.swiftCode());
        if (request.addressLine1() != null) beneficiary.setAddressLine1(request.addressLine1());
        if (request.city() != null) beneficiary.setCity(request.city());
        
        Beneficiary saved = beneficiaryRepository.save(beneficiary);
        return ResponseEntity.ok(ApiResponse.success(saved));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteBeneficiary(@PathVariable UUID id) {
        Beneficiary beneficiary = beneficiaryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Beneficiary not found: " + id));
        
        beneficiaryRepository.delete(beneficiary);
        log.info("Deleted beneficiary: {}", id);
        
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PostMapping("/{id}/verify")
    public ResponseEntity<ApiResponse<Beneficiary>> verifyBeneficiary(@PathVariable UUID id) {
        Beneficiary beneficiary = beneficiaryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Beneficiary not found: " + id));
        
        beneficiary.setValidationStatus(ValidationStatus.VERIFIED);
        
        Beneficiary saved = beneficiaryRepository.save(beneficiary);
        return ResponseEntity.ok(ApiResponse.success(saved));
    }

    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("total", beneficiaryRepository.count());
        stats.put("active", beneficiaryRepository.countByStatus(BeneficiaryStatus.ACTIVE));
        stats.put("verified", beneficiaryRepository.countByValidationStatus(ValidationStatus.VERIFIED));
        return ResponseEntity.ok(ApiResponse.success(stats));
    }

    // Request DTOs
    public record CreateBeneficiaryRequest(
        UUID corporateId,
        String beneficiaryName,
        String beneficiaryType,
        String bankName,
        String swiftCode,
        String accountNumber,
        String iban,
        String currencyCode,
        String countryCode,
        String city,
        String addressLine1
    ) {}

    public record UpdateBeneficiaryRequest(
        String beneficiaryName,
        String bankName,
        String accountNumber,
        String iban,
        String swiftCode,
        String addressLine1,
        String city
    ) {}
}