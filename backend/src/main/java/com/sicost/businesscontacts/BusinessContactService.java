package com.sicost.businesscontacts;

import com.sicost.authentication.AuthenticationMapper;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.authentication.AuthenticationService.values;

@Service
public class BusinessContactService {
    private final BusinessContactMapper mapper;
    private final PermissionService permissions;
    private final CurrentUser user;
    private final AuthenticationMapper audit;
    private final Clock clock;
    public BusinessContactService(BusinessContactMapper mapper,PermissionService permissions,CurrentUser user,AuthenticationMapper audit,Clock clock) {this.mapper=mapper;this.permissions=permissions;this.user=user;this.audit=audit;this.clock=clock;}
    @Transactional public Map<String,Object> contact(boolean developer,long id,String purpose) {
        String screen=developer?"UI-02":"UI-15";permissions.can(screen+".read");
        if(purpose==null || !(developer?Set.of("STAFF_OPERATION"):Set.of("CONTRACT_SALES","BILLING_RECEIPT_PAYMENT")).contains(purpose)) throw ApiException.forbidden();
        var now=SecurityTime.now(clock);var query=values("id",id,"userId",user.userId(),"screenId",screen,"purpose",purpose,"today",now.toLocalDate());
        var row=developer?mapper.developer(query):mapper.partner(query);if(row==null || row.isEmpty()) throw ApiException.notFound("연락처를 조회할 수 없습니다.");
        audit.audit(values("id",user.userId(),"action","CONTACT_VIEW","screenId",screen,"target",Long.toString(id),"detail",purpose,"ip",null,"now",now));
        return values("phone",row.get("phone"),"email",row.get("email"),"masked",row.get("masked"));
    }
}
