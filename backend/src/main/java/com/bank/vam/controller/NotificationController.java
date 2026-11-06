package com.bank.vam.controller;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.service.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** The header bell: a corporate's latest notifications (all corporates' without one), and marking them read. */
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notifications;

    @GetMapping
    public ApiResponse<NotificationService.Inbox> inbox(@RequestParam(required = false) UUID corporateId) {
        return ApiResponse.success(notifications.inbox(corporateId));
    }

    @PostMapping("/{id}/read")
    public ApiResponse<Void> read(@PathVariable UUID id) {
        notifications.markRead(id);
        return ApiResponse.success(null);
    }

    @PostMapping("/read-all")
    public ApiResponse<Integer> readAll(@RequestParam(required = false) UUID corporateId) {
        return ApiResponse.success(notifications.markAllRead(corporateId));
    }
}
