package com.bank.vam.controller.treasury;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.entity.treasury.ExternalMandate;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.service.treasury.ExternalAuthorityService;
import com.bank.vam.service.treasury.ExternalAuthorityService.Registration;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Authorities for the home bank to instruct an account at another bank (spec external-bank-sweeps R1, R2): list,
 * register (PENDING_ACTIVATION), activate, suspend, revoke.
 * ponytail: open like every endpoint today (SecurityConfig permits all); spec D3 makes the changes bank operations'
 * only and the corporate's view read-only — restrict the POSTs to a bank-operations role when sign-in is turned on.
 */
@RestController
@RequestMapping("/api/v1/treasury/external-authorities")
@RequiredArgsConstructor
public class ExternalAuthorityController {

    private final ExternalAuthorityService service;

    public record Revocation(String reason) {
    }

    /** By shadow ({@code ?shadowVaId=}) or by corporate ({@code ?corporateId=}), newest first. */
    @GetMapping
    public ApiResponse<List<ExternalMandate>> list(@RequestParam(required = false) UUID shadowVaId,
                                                   @RequestParam(required = false) UUID corporateId) {
        if (shadowVaId != null) return ApiResponse.success(service.forShadow(shadowVaId));
        if (corporateId != null) return ApiResponse.success(service.forCorporate(corporateId));
        throw new BusinessException("shadowVaId or corporateId is required");
    }

    @PostMapping
    public ApiResponse<ExternalMandate> register(@RequestBody Registration registration) {
        return ApiResponse.success(service.register(registration), "Authority registered, pending activation");
    }

    @PostMapping("/{id}/activate")
    public ApiResponse<ExternalMandate> activate(@PathVariable UUID id) {
        return ApiResponse.success(service.activate(id), "Authority activated");
    }

    @PostMapping("/{id}/suspend")
    public ApiResponse<ExternalMandate> suspend(@PathVariable UUID id) {
        return ApiResponse.success(service.suspend(id), "Authority suspended");
    }

    @PostMapping("/{id}/revoke")
    public ApiResponse<ExternalMandate> revoke(@PathVariable UUID id, @RequestBody Revocation revocation) {
        return ApiResponse.success(service.revoke(id, revocation.reason()), "Authority revoked");
    }
}
