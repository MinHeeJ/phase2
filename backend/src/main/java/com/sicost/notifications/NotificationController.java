package com.sicost.notifications;

import com.sicost.common.api.ApiResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
    private final NotificationService service;
    public NotificationController(NotificationService service) { this.service=service; }
    @GetMapping public ApiResponse<?> listNotifications(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,@RequestParam(defaultValue="false") boolean unreadOnly) { return ApiResponse.ok(service.list(page,size,unreadOnly)); }
    @GetMapping("/unread-count") public ApiResponse<?> getNotificationUnreadCount() { return ApiResponse.ok(service.count()); }
    @GetMapping("/{id}") public ApiResponse<?> getNotification(@PathVariable Long id) { return ApiResponse.ok(service.detail(id)); }
    @PatchMapping("/{id}/read") public ApiResponse<Void> readNotification(@PathVariable Long id) { service.read(id);return ApiResponse.ok(); }
    @PostMapping("/read-all") public ApiResponse<Void> saveNotificationsReadAll() { service.readAll();return ApiResponse.ok(); }
}
