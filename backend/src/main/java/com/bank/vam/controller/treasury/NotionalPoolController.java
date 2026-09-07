package com.bank.vam.controller.treasury;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.dto.treasury.NotionalPoolDto;
import com.bank.vam.service.treasury.NotionalPoolService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/pooling")
@RequiredArgsConstructor
@Tag(name = "Notional Pooling", description = "Notional pool management")
public class NotionalPoolController {

    private final NotionalPoolService poolService;

    @GetMapping
    @Operation(summary = "Get all notional pools")
    public ResponseEntity<ApiResponse<List<NotionalPoolDto.Response>>> getAllPools() {
        return ResponseEntity.ok(ApiResponse.success(poolService.getAllPools()));
    }

    @GetMapping("/active")
    @Operation(summary = "Get active pools")
    public ResponseEntity<ApiResponse<List<NotionalPoolDto.Response>>> getActivePools() {
        return ResponseEntity.ok(ApiResponse.success(poolService.getActivePools()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get pool by ID")
    public ResponseEntity<ApiResponse<NotionalPoolDto.Response>> getPoolById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(poolService.getPoolById(id)));
    }

    @PostMapping
    @Operation(summary = "Create new pool")
    public ResponseEntity<ApiResponse<NotionalPoolDto.Response>> createPool(
            @RequestBody NotionalPoolDto.CreateRequest request) {
        return ResponseEntity.ok(ApiResponse.success(poolService.createPool(request), "Pool created"));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update pool")
    public ResponseEntity<ApiResponse<NotionalPoolDto.Response>> updatePool(
            @PathVariable UUID id, @RequestBody NotionalPoolDto.UpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.success(poolService.updatePool(id, request), "Pool updated"));
    }

    @PostMapping("/{id}/members")
    @Operation(summary = "Add member to pool")
    public ResponseEntity<ApiResponse<NotionalPoolDto.Response>> addMember(
            @PathVariable UUID id, @RequestBody NotionalPoolDto.MemberRequest request) {
        return ResponseEntity.ok(ApiResponse.success(poolService.addMember(id, request), "Member added"));
    }

    @PostMapping("/{id}/members/bulk")
    @Operation(summary = "Bulk-add members to pool")
    public ResponseEntity<ApiResponse<NotionalPoolDto.BulkAddMembersResponse>> addMembersBulk(
            @PathVariable UUID id, @RequestBody NotionalPoolDto.BulkAddMembersRequest request) {
        return ResponseEntity.ok(ApiResponse.success(poolService.addMembersBulk(id, request), "Members processed"));
    }

    @DeleteMapping("/{poolId}/members/{memberId}")
    @Operation(summary = "Remove member from pool")
    public ResponseEntity<ApiResponse<Void>> removeMember(
            @PathVariable UUID poolId, @PathVariable UUID memberId) {
        poolService.removeMember(poolId, memberId);
        return ResponseEntity.ok(ApiResponse.success(null, "Member removed"));
    }

    @PostMapping("/{id}/calculate")
    @Operation(summary = "Calculate interest for pool")
    public ResponseEntity<ApiResponse<NotionalPoolDto.CalculateInterestResponse>> calculateInterest(
            @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(poolService.calculateInterest(id)));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete pool")
    public ResponseEntity<ApiResponse<Void>> deletePool(@PathVariable UUID id) {
        poolService.deletePool(id);
        return ResponseEntity.ok(ApiResponse.success(null, "Pool deleted"));
    }
}
