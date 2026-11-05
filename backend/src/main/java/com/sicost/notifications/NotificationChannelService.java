package com.sicost.notifications;

import com.sicost.common.api.*;
import com.sicost.common.security.*;
import com.sicost.systemmanagement.SystemManagementMapper;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationChannelService {
    private final SystemManagementMapper mapper;
    private final PermissionService permissions;
    private final CurrentUser user;
    private final Clock clock;
    public NotificationChannelService(SystemManagementMapper mapper,PermissionService permissions,CurrentUser user,Clock clock) { this.mapper=mapper;this.permissions=permissions;this.user=user;this.clock=clock; }
    private String month() { return YearMonth.now(clock.withZone(ZoneId.of("Asia/Seoul"))).toString(); }
    public Map<String,Object> get() { permissions.can("UI-25.read");return Map.of("channels",mapper.channels(month())); }
    public record Channel(String type,Boolean emailEnabled) {}
    public record Channels(List<Channel> channels) {}
    @Transactional public void save(Channels input) {
        permissions.can("UI-25.update");if(input.channels()==null) throw ApiException.badRequest("채널을 확인하세요.","channels");
        Set<String> allowed=new HashSet<>();mapper.channels(month()).forEach(c->allowed.add((String)c.get("type")));Set<String> seen=new HashSet<>();
        for(var c:input.channels()) if(c==null || c.emailEnabled()==null || !allowed.contains(c.type()) || !seen.add(c.type())) throw ApiException.badRequest("업무 채널만 설정할 수 있습니다.","channels");
        for(var c:input.channels()) mapper.saveChannel(Map.of("type",c.type(),"emailEnabled",c.emailEnabled(),"month",month(),"actor",user.userId()));
        Map<String,Object> event=new HashMap<>();event.put("actor",user.userId());event.put("screenId","UI-25");event.put("type","PERM_CHANGE");event.put("target","notification-channels");event.put("detail","업무 이메일 채널 설정");mapper.audit(event);
    }
}
