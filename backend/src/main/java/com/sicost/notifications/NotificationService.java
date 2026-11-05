package com.sicost.notifications;

import com.sicost.common.api.ApiException;
import com.sicost.common.security.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly=true)
public class NotificationService {
    private final NotificationMapper mapper;
    private final CurrentUser user;
    private final Clock clock;
    public NotificationService(NotificationMapper mapper,CurrentUser user,Clock clock) { this.mapper=mapper;this.user=user;this.clock=clock; }
    private Map<String,Object> query() { return new HashMap<>(Map.of("userId",user.userId(),"now",LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul"))),"unreadOnly",false)); }
    public Map<String,Object> list(int page,int size,boolean unreadOnly) {
        if(page<0 || !Set.of(20,50,100).contains(size)) throw ApiException.badRequest("페이지를 확인하세요.","page");
        var q=query();q.put("size",size);q.put("offset",(long)page*size);q.put("unreadOnly",unreadOnly);
        return Map.of("items",mapper.list(q).stream().map(this::project).toList(),"total",mapper.count(q),"page",page,"size",size);
    }
    public Map<String,Long> unread() { var q=query();q.put("unreadOnly",true);return Map.of("count",mapper.count(q)); }
    private Map<String,Object> require(Map<String,Object> q) {
        var row=mapper.owned(q);if(row==null) throw ApiException.notFound("알림이 없습니다.");
        if(!mapper.visible(q)) throw PermissionService.forbidden();return row;
    }
    public Map<String,Object> get(Long id) { var q=query();q.put("id",id);return project(require(q)); }
    @Transactional public void read(Long id) {
        var q=query();q.put("id",id);require(q);if(mapper.read(q)!=1) throw PermissionService.forbidden();
    }
    @Transactional public void readAll() { mapper.readAll(query()); }
    private Map<String,Object> project(Map<String,Object> row) {
        Map<String,Object> link=new LinkedHashMap<>();
        Object project=row.remove("projectId"),part=row.remove("partId");
        if(project!=null) link.put("projectId",project);if(part!=null) link.put("partId",part);
        row.put("linkParams",link.isEmpty()?null:link);return row;
    }
}
