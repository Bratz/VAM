package com.bank.vam.controller;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.entity.Corporate;
import com.bank.vam.entity.Corporate.CorporateStatus;
import com.bank.vam.entity.Corporate.KycStatus;
import com.bank.vam.repository.CorporateRepository;
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
@RequestMapping("/api/v1/corporates")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class CorporateController {

    private final CorporateRepository corporateRepository;

    @GetMapping
    public ResponseEntity<ApiResponse<List<Corporate>>> getAllCorporates(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status) {
        
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<Corporate> corporates = corporateRepository.findAll(pageRequest);
        
        return ResponseEntity.ok(ApiResponse.success(corporates.getContent()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Corporate>> getCorporateById(@PathVariable UUID id) {
        Corporate corporate = corporateRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Corporate not found: " + id));
        return ResponseEntity.ok(ApiResponse.success(corporate));
    }

    @GetMapping("/code/{code}")
    public ResponseEntity<ApiResponse<Corporate>> getCorporateByCode(@PathVariable String code) {
        Corporate corporate = corporateRepository.findByCorporateId(code)
                .orElseThrow(() -> new ResourceNotFoundException("Corporate not found: " + code));
        return ResponseEntity.ok(ApiResponse.success(corporate));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Corporate>> createCorporate(@RequestBody CreateCorporateRequest request) {
        Corporate corporate = Corporate.builder()
                .corporateId(request.corporateId())
                .legalName(request.legalName())
                .tradeName(request.tradeName())
                .registrationNumber(request.registrationNumber())
                .taxId(request.taxId())
                .incorporationCountry(request.incorporationCountry())
                .industrySector(request.industrySector())
                .primaryContactName(request.primaryContactName())
                .primaryContactEmail(request.primaryContactEmail())
                .primaryContactPhone(request.primaryContactPhone())
                .status(CorporateStatus.ACTIVE)
                .kycStatus(KycStatus.PENDING)
                .build();
        
        Corporate saved = corporateRepository.save(corporate);
        log.info("Created corporate: {}", saved.getCorporateId());
        
        return ResponseEntity.ok(ApiResponse.success(saved));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<Corporate>> updateCorporate(
            @PathVariable UUID id, 
            @RequestBody UpdateCorporateRequest request) {
        
        Corporate corporate = corporateRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Corporate not found: " + id));
        
        if (request.legalName() != null) corporate.setLegalName(request.legalName());
        if (request.tradeName() != null) corporate.setTradeName(request.tradeName());
        if (request.industrySector() != null) corporate.setIndustrySector(request.industrySector());
        if (request.primaryContactName() != null) corporate.setPrimaryContactName(request.primaryContactName());
        if (request.primaryContactEmail() != null) corporate.setPrimaryContactEmail(request.primaryContactEmail());
        if (request.primaryContactPhone() != null) corporate.setPrimaryContactPhone(request.primaryContactPhone());
        
        Corporate saved = corporateRepository.save(corporate);
        return ResponseEntity.ok(ApiResponse.success(saved));
    }

    @PostMapping("/{id}/kyc/approve")
    public ResponseEntity<ApiResponse<Corporate>> approveKyc(@PathVariable UUID id) {
        Corporate corporate = corporateRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Corporate not found: " + id));
        
        corporate.setKycStatus(KycStatus.APPROVED);
        
        Corporate saved = corporateRepository.save(corporate);
        log.info("Approved KYC for corporate: {}", corporate.getCorporateId());
        
        return ResponseEntity.ok(ApiResponse.success(saved));
    }

    @PostMapping("/{id}/status")
    public ResponseEntity<ApiResponse<Corporate>> updateStatus(
            @PathVariable UUID id,
            @RequestParam String status) {
        
        Corporate corporate = corporateRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Corporate not found: " + id));
        
        corporate.setStatus(CorporateStatus.valueOf(status));
        Corporate saved = corporateRepository.save(corporate);
        
        return ResponseEntity.ok(ApiResponse.success(saved));
    }

    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("total", corporateRepository.count());
        stats.put("active", corporateRepository.countByStatus(CorporateStatus.ACTIVE));
        stats.put("pending", corporateRepository.countByKycStatus(KycStatus.PENDING));
        stats.put("verified", corporateRepository.countByKycStatus(KycStatus.APPROVED));
        return ResponseEntity.ok(ApiResponse.success(stats));
    }

    // Request DTOs
    public record CreateCorporateRequest(
        String corporateId,
        String legalName,
        String tradeName,
        String registrationNumber,
        String taxId,
        String incorporationCountry,
        String industrySector,
        String primaryContactName,
        String primaryContactEmail,
        String primaryContactPhone
    ) {}

    public record UpdateCorporateRequest(
        String legalName,
        String tradeName,
        String industrySector,
        String primaryContactName,
        String primaryContactEmail,
        String primaryContactPhone
    ) {}
}