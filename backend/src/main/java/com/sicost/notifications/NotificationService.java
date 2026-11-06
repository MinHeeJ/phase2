package com.sicost.notifications;

import com.sicost.common.api.*;
import com.sicost.common.security.*;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.authentication.AuthenticationService.values;

@Service
@Transactional(readOnly=true)
public class NotificationService {
    private final NotificationMapper mapper;
    private final CurrentUser user;
    private final Clock clock;
    public NotificationService(NotificationMapper mapper,CurrentUser user,Clock clock) {this.mapper=mapper;this.user=user;this.clock=clock;}
    private Map<String,Object> query() {return values("userId",user.userId(),"now",SecurityTime.now(clock));}
    public Map<String,Object> list(Map<String,String> filters) {
        var q=query();int page,size;
        try {page=Integer.parseInt(filters.getOrDefault("page","0"));size=Integer.parseInt(filters.getOrDefault("size","20"));if(page<0 || size<1 || size>100) throw new NumberFormatException();q.put("offset",Math.multiplyExact(page,size));}
        catch(IllegalArgumentException|ArithmeticException ex) {throw ApiException.badRequest("페이지를 확인하세요.","page");}
        q.put("size",size);q.put("type",filters.get("type"));q.put("search",filters.get("search"));
        for(String key:List.of("unreadOnly","resolved")) if(filters.containsKey(key) && !filters.get(key).isBlank()) {if(!Set.of("true","false").contains(filters.get(key))) throw ApiException.badRequest("필터를 확인하세요.",key);q.put(key,Boolean.valueOf(filters.get(key)));}
        return values("items",mapper.list(q),"total",mapper.count(q),"page",page,"size",size);
    }
    public Map<String,Object> count() {var q=query();q.put("unreadOnly",true);return Map.of("unreadCount",mapper.count(q));}
    public Map<String,Object> detail(long id) {var q=query();q.put("id",id);var owned=mapper.owned(q);if(owned==null || owned.isEmpty()) throw ApiException.notFound("알림이 없습니다.");var row=mapper.detail(q);if(row==null || row.isEmpty()) throw ApiException.forbidden();return row;}
    @Transactional public Map<String,Object> read(long id) {var row=detail(id);var q=query();q.put("id",id);if(mapper.read(q)!=1) throw ApiException.forbidden();return values("id",id,"read",true,"unreadCount",count().get("unreadCount"),"targetScreenId",row.get("targetScreenId"),"targetId",row.get("targetId"));}
    @Transactional public Map<String,Object> readAll() {int changed=mapper.readAll(query());return values("updatedCount",changed,"unreadCount",count().get("unreadCount"));}
}
