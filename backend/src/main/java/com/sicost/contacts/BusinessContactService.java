package com.sicost.contacts;

import com.sicost.common.api.*;
import com.sicost.common.security.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BusinessContactService {
    private final BusinessContactMapper mapper;
    private final PermissionService permissions;
    private final CurrentUser user;
    private final Clock clock;
    public BusinessContactService(BusinessContactMapper mapper,PermissionService permissions,CurrentUser user,Clock clock) { this.mapper=mapper;this.permissions=permissions;this.user=user;this.clock=clock; }
    @Transactional public Map<String,Object> read(String kind,Long id,String purpose) {
        String screen="developer".equals(kind)?"UI-02":"UI-15";
        var scope=permissions.scope(screen+".read");
        if(purpose==null || !("developer".equals(kind)?Set.of("STAFF_OPERATION"):Set.of("CONTRACT_SALES","BILLING_RECEIPT_PAYMENT")).contains(purpose)) throw PermissionService.forbidden();
        Set<Long> purposeRoles="developer".equals(kind)?Set.of(4L,5L):"CONTRACT_SALES".equals(purpose)?Set.of(4L):Set.of(3L);
        if(scope.grants().stream().noneMatch(g->purposeRoles.contains(g.roleId()))) throw PermissionService.forbidden();
        var now=LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        Map<String,Object> q=new HashMap<>();q.put("userId",user.userId());q.put("id",id);q.put("purpose",purpose);q.put("kind",kind);q.put("screenId",screen);q.put("now",now);q.put("today",now.toLocalDate());q.put("grants",scope.grants());
        if(!mapper.allowed(q)) throw ApiException.notFound("요청한 항목이 없습니다.");
        Map<String,Object> row="developer".equals(kind)?mapper.developer(q):mapper.partner(q);
        if(row==null) throw ApiException.notFound("요청한 항목이 없습니다.");
        if(Boolean.TRUE.equals(row.get("masked"))) { row.put("phone",null);row.put("email",null); }
        else mapper.audit(q);
        return row;
    }
}
