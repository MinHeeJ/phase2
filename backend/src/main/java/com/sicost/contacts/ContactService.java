package com.sicost.contacts;

import com.sicost.common.api.*;
import com.sicost.common.security.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.common.security.SecurityAuditService.*;
import static com.sicost.common.security.BusinessContactPolicy.Target;

@Service
public class ContactService {
    public record ContactView(String phone,String email) {}
    private final ContactMapper mapper;
    private final AuthorizationService authorization;
    private final BusinessContactPolicy policy;
    private final SecurityAuditService audit;
    public ContactService(ContactMapper mapper,AuthorizationService authorization,BusinessContactPolicy policy,SecurityAuditService audit) {
        this.mapper=mapper;this.authorization=authorization;this.policy=policy;this.audit=audit;
    }
    @Transactional public ContactView view(Long id,String purpose,boolean partner) {
        String screen=partner?"UI-15":"UI-02";var scope=authorization.require(screen+".read");
        var resources=partner?mapper.partnerResources(id):mapper.developerResources(id);
        var accessible=resources.stream().filter(scope::allows).toList();
        if(accessible.isEmpty()) throw ApiException.notFound("요청한 항목이 없습니다.");
        Purpose selected;try { selected=Purpose.valueOf(purpose); } catch(IllegalArgumentException | NullPointerException ex) { throw AuthorizationService.forbidden(); }
        var contact=partner?mapper.partner(id):mapper.developer(id);if(contact==null) throw ApiException.notFound("요청한 항목이 없습니다.");
        boolean granted=false;
        for(var resource:accessible) {
            try { policy.require(partner?Target.PARTNER:Target.DEVELOPER,resource,selected,contact.active(),false);granted=true;break; }
            catch(ApiException ex) { if(ex.status()!=403) throw ex; }
        }
        if(!granted) throw AuthorizationService.forbidden();
        audit.record(Event.CONTACT_VIEW,screen,id,Result.SUCCESS,selected);
        boolean finance=selected==Purpose.BILLING || selected==Purpose.RECEIPT || selected==Purpose.PAYMENT;
        return new ContactView(contact.phone(),finance && contact.financialEmail()!=null?contact.financialEmail():contact.email());
    }
}
