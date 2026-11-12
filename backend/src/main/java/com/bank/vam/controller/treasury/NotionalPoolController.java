package com.bank.vam.controller.treasury;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.dto.treasury.NotionalPoolDto;
import com.bank.vam.service.treasury.NotionalPoolService;
import com.bank.vam.service.treasury.PoolPostingService;
import com.bank.vam.service.treasury.PoolRunService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/pooling")
@RequiredArgsConstructor
@Tag(name = "Notional Pooling", description = "Notional pool management")
public class NotionalPoolController {

    private final NotionalPoolService poolService;
    private final PoolRunService runService;
    private final PoolPostingService postingService;

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
    @Operation(summary = "Member leaves the pool (dated; the row is kept)")
    public ResponseEntity<ApiResponse<Void>> removeMember(
            @PathVariable UUID poolId, @PathVariable UUID memberId) {
        poolService.removeMember(poolId, memberId);
        return ResponseEntity.ok(ApiResponse.success(null, "Member removed"));
    }

    @PostMapping("/{id}/suspend")
    @Operation(summary = "Suspend an active pool")
    public ResponseEntity<ApiResponse<NotionalPoolDto.Response>> suspendPool(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(poolService.suspendPool(id), "Pool suspended"));
    }

    @PostMapping("/{id}/close")
    @Operation(summary = "Close an active or suspended pool; its members leave")
    public ResponseEntity<ApiResponse<NotionalPoolDto.Response>> closePool(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(poolService.closePool(id), "Pool closed"));
    }

    @PostMapping("/{id}/runs")
    @Operation(summary = "Daily runs for value dates from..to (catch-up, at most 92 days, before today); dates already run are returned unchanged")
    public ResponseEntity<ApiResponse<List<NotionalPoolDto.Run>>> runDates(@PathVariable UUID id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ApiResponse.success(runService.runRange(id, from, to)));
    }

    @GetMapping("/{id}/runs")
    @Operation(summary = "Stored daily runs, newest first (default: the last 31 days)")
    public ResponseEntity<ApiResponse<List<NotionalPoolDto.Run>>> runs(@PathVariable UUID id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ApiResponse.success(runService.history(id, from, to)));
    }

    @PostMapping("/{id}/runs/{date}/correct")
    @Operation(summary = "Correct a date: the run is superseded, a new one made; if it was posted, the difference is posted now")
    public ResponseEntity<ApiResponse<NotionalPoolDto.Run>> correctRun(@PathVariable UUID id,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(ApiResponse.success(postingService.correct(id, date), "Run corrected"));
    }

    @PostMapping("/{id}/postings")
    @Operation(summary = "Post a past month (yyyy-MM): missing dates run, one interest movement per member; posting again posts nothing new")
    public ResponseEntity<ApiResponse<List<NotionalPoolDto.Posting>>> postMonth(@PathVariable UUID id,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return ResponseEntity.ok(ApiResponse.success(postingService.postMonth(id, month)));
    }

    @GetMapping("/{id}/postings")
    @Operation(summary = "Interest postings (month and corrections), newest first")
    public ResponseEntity<ApiResponse<List<NotionalPoolDto.Posting>>> postings(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(postingService.history(id)));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete pool")
    public ResponseEntity<ApiResponse<Void>> deletePool(@PathVariable UUID id) {
        poolService.deletePool(id);
        return ResponseEntity.ok(ApiResponse.success(null, "Pool deleted"));
    }
}
