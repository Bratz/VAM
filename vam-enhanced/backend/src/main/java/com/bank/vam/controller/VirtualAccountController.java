package com.bank.vam.controller;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.dto.VirtualAccountDto;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.service.VirtualAccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/virtual-accounts")
@RequiredArgsConstructor
public class VirtualAccountController {

    private final VirtualAccountService virtualAccountService;

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<VirtualAccount>> getById(@PathVariable UUID id) {
        VirtualAccount va = virtualAccountService.getById(id);
        return ResponseEntity.ok(ApiResponse.success(va));
    }

    @GetMapping("/by-number/{vaNumber}")
    public ResponseEntity<ApiResponse<VirtualAccount>> getByVaNumber(@PathVariable String vaNumber) {
        VirtualAccount va = virtualAccountService.getByVaNumber(vaNumber);
        return ResponseEntity.ok(ApiResponse.success(va));
    }

    @GetMapping("/by-viban/{viban}")
    public ResponseEntity<ApiResponse<VirtualAccount>> getByViban(@PathVariable String viban) {
        VirtualAccount va = virtualAccountService.getByViban(viban);
        return ResponseEntity.ok(ApiResponse.success(va));
    }

    @GetMapping("/corporate/{corporateId}")
    public ResponseEntity<ApiResponse<List<VirtualAccount>>> getByCorporateId(
            @PathVariable UUID corporateId,
            Pageable pageable) {
        Page<VirtualAccount> page = virtualAccountService.getByCorporateId(corporateId, pageable);
        return ResponseEntity.ok(ApiResponse.paged(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements()));
    }

    @GetMapping("/program/{programId}")
    public ResponseEntity<ApiResponse<List<VirtualAccount>>> getByProgramId(
            @PathVariable UUID programId,
            Pageable pageable) {
        Page<VirtualAccount> page = virtualAccountService.getByProgramId(programId, pageable);
        return ResponseEntity.ok(ApiResponse.paged(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements()));
    }

    @GetMapping("/search")
    public ResponseEntity<ApiResponse<List<VirtualAccount>>> search(
            @RequestParam String query,
            Pageable pageable) {
        Page<VirtualAccount> page = virtualAccountService.search(query, pageable);
        return ResponseEntity.ok(ApiResponse.paged(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements()));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<VirtualAccount>> create(
            @RequestBody VirtualAccountDto.CreateRequest request) {
        VirtualAccount va = virtualAccountService.create(request);
        return ResponseEntity.ok(ApiResponse.success(va, "Virtual account created successfully"));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<VirtualAccount>> update(
            @PathVariable UUID id,
            @RequestBody VirtualAccountDto.UpdateRequest request) {
        VirtualAccount va = virtualAccountService.update(id, request);
        return ResponseEntity.ok(ApiResponse.success(va, "Virtual account updated successfully"));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<ApiResponse<VirtualAccount>> updateStatus(
            @PathVariable UUID id,
            @RequestParam VirtualAccount.VaStatus status) {
        VirtualAccount va = virtualAccountService.updateStatus(id, status);
        return ResponseEntity.ok(ApiResponse.success(va, "Status updated successfully"));
    }
}
