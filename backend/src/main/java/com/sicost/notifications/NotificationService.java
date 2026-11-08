package com.sicost.notifications;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly=true)
public class NotificationService {
    private final NotificationMapper mapper;
    private final CurrentUser current;
    private final ObjectMapper json;
    private final Clock clock;
    public NotificationService(NotificationMapper mapper,CurrentUser current,ObjectMapper json,Clock clock) { this.mapper=mapper;this.current=current;this.json=json;this.clock=clock; }
    private Map<String,Object> query() {
        var principal=current.principal();if(principal.passwordChangeOnly()) throw AuthorizationService.forbidden();
        return new HashMap<>(Map.of("userId",principal.userId(),"now",LocalDateTime.now(clock.withZone(SessionService.SEOUL))));
    }
    private Map<String,Object> projection(Map<String,Object> source) {
        var row=new LinkedHashMap<>(source);Object raw=row.remove("targetJson");Map<String,Object> safe=new LinkedHashMap<>();
        try {var target=json.readTree(raw==null?"{}":raw.toString());for(String key:List.of("projectId","partId","userId","developerId","uploadId","month")) if(target.hasNonNull(key) && (target.get(key).isIntegralNumber() || target.get(key).isTextual())) safe.put(key,target.get(key).isIntegralNumber()?target.get(key).longValue():target.get(key).asText());}
        catch(com.fasterxml.jackson.core.JsonProcessingException ex) { /* Legacy links are not trusted navigation state. */ }
        row.put("target",safe);return row;
    }
    public PageResult<Map<String,Object>> list(int page,int size,boolean unreadOnly) {
        if(page<0 || !Set.of(20,50,100).contains(size) || page>Integer.MAX_VALUE/size) throw ApiException.badRequest("페이지를 확인하세요.","page");
        var q=query();q.put("size",size);q.put("offset",page*size);q.put("unreadOnly",unreadOnly);
        return new PageResult<>(mapper.list(q).stream().map(this::projection).toList(),mapper.count(q),page,size);
    }
    public Map<String,Long> count() { var q=query();q.put("unreadOnly",true);return Map.of("count",mapper.count(q)); }
    private Map<String,Object> own(Long id,Map<String,Object> q) {
        q.put("id",id);var row=mapper.own(q);if(row==null) throw ApiException.notFound("알림이 없습니다.");
        if(!mapper.visible(q)) throw AuthorizationService.forbidden();return row;
    }
    public Map<String,Object> detail(Long id) { var q=query();return projection(own(id,q)); }
    @Transactional public void read(Long id) { var q=query();own(id,q);mapper.read(q); }
    @Transactional public void readAll() { mapper.readAll(query()); }
}
