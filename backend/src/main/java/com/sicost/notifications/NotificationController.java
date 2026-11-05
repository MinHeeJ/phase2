package com.sicost.notifications;

import com.sicost.common.api.ApiResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
    private final NotificationService service;
    public NotificationController(NotificationService service) { this.service=service; }
    @GetMapping public ApiResponse<Map<String,Object>> list(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,@RequestParam(defaultValue="false") boolean unreadOnly) { return ApiResponse.ok(service.list(page,size,unreadOnly)); }
    @GetMapping("/unread-count") public ApiResponse<Map<String,Long>> unread() { return ApiResponse.ok(service.unread()); }
    @GetMapping("/{id}") public ApiResponse<Map<String,Object>> get(@PathVariable Long id) { return ApiResponse.ok(service.get(id)); }
    @PatchMapping("/{id}/read") public ApiResponse<Void> read(@PathVariable Long id) { service.read(id);return ApiResponse.ok(); }
    @PostMapping("/read-all") public ApiResponse<Void> readAll() { service.readAll();return ApiResponse.ok(); }
}
