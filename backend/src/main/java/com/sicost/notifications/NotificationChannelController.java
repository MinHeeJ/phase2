package com.sicost.notifications;

import com.sicost.common.api.ApiResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/notification-channels")
public class NotificationChannelController {
    private final NotificationChannelService service;
    public NotificationChannelController(NotificationChannelService service) { this.service=service; }
    @GetMapping public ApiResponse<Map<String,Object>> get() { return ApiResponse.ok(service.get()); }
    @PutMapping public ApiResponse<Void> save(@RequestBody NotificationChannelService.Channels input) { service.save(input);return ApiResponse.ok(); }
}
