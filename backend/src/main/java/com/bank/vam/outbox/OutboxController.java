package com.bank.vam.outbox;

import com.bank.vam.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** The simulated outbox: emails the platform would have sent. */
@RestController
@RequestMapping("/api/v1/outbox")
@RequiredArgsConstructor
public class OutboxController {

    private final OutboxEmailRepository emails;

    /** The corporate's latest 200 emails, newest first; or those about one record ({@code relatedId}). */
    @GetMapping
    public ApiResponse<List<OutboxEmail>> list(@RequestParam(required = false) UUID corporateId,
                                               @RequestParam(required = false) UUID relatedId) {
        if (relatedId != null) return ApiResponse.success(emails.findByRelatedIdOrderByCreatedAtDesc(relatedId));
        if (corporateId == null) throw new com.bank.vam.exception.BusinessException("corporateId or relatedId is required");
        return ApiResponse.success(emails.findTop200ByCorporateIdOrderByCreatedAtDesc(corporateId));
    }
}
