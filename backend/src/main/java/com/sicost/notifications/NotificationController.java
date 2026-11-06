package com.sicost.notifications;

import com.sicost.common.api.ApiResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
    private final NotificationService service;
    public NotificationController(NotificationService service) {this.service=service;}
    @GetMapping public ApiResponse<Map<String,Object>> listNotifications(@RequestParam Map<String,String> filters) {return ApiResponse.ok(service.list(filters));}
    @GetMapping("/unread-count") public ApiResponse<Map<String,Object>> getNotificationUnreadCount() {return ApiResponse.ok(service.count());}
    @GetMapping("/{id}") public ApiResponse<Map<String,Object>> getNotification(@PathVariable long id) {return ApiResponse.ok(service.detail(id));}
    @PatchMapping("/{id}/read") public ApiResponse<Map<String,Object>> updateNotificationRead(@PathVariable long id) {return ApiResponse.ok(service.read(id));}
    @PostMapping("/read-all") public ApiResponse<Map<String,Object>> saveNotificationsReadAll() {return ApiResponse.ok(service.readAll());}
}
