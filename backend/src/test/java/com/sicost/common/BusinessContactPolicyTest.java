package com.sicost.common;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sicost.common.security.*;
import com.sicost.common.security.SecurityModels.*;
import com.sicost.common.security.SecurityAuditService.Purpose;
import com.sicost.common.security.BusinessContactPolicy.Target;
import java.util.List;
import org.junit.jupiter.api.Test;

class BusinessContactPolicyTest {
    final AuthorizationService authorization=mock(AuthorizationService.class);
    final BusinessContactPolicy policy=new BusinessContactPolicy(authorization);
    final Resource row=new Resource(null,1L,10L,101L);
    void scope(String role,DataScope type,String key,boolean view,boolean download) {
        var grant=new Permission(4L,role,type,false,true,false,key.substring(0,5),"YYYYYYY",view,download);
        when(authorization.requireRow(key,row)).thenReturn(new ScopePredicate(11L,null,new Assignments(List.of(1L),List.of(10L)),List.of(grant)));
    }
    @Test void assignedPmMayViewDeveloperForStaffOperation() { scope("일반 사용자(PM)",DataScope.PROJECT,"UI-02.read",true,false);policy.require(Target.DEVELOPER,row,Purpose.STAFF_OPERATION,true,false); }
    @Test void salesMayViewOwnPartPartnerForSalesPurpose() { scope("영업 파트장",DataScope.PART,"UI-15.read",true,false);policy.require(Target.PARTNER,row,Purpose.CONTRACT_SALES,true,false); }
    @Test void financeDeveloperContactIsDeniedDespiteSensitiveMoneyPermission() { scope("재무 담당",DataScope.ALL,"UI-02.read",true,false);AuthorizationTest.status(()->policy.require(Target.DEVELOPER,row,Purpose.STAFF_OPERATION,true,false),403); }
    @Test void executiveContactIsMaskedByDefault() { scope("경영진",DataScope.ALL,"UI-02.read",true,false);AuthorizationTest.status(()->policy.require(Target.DEVELOPER,row,Purpose.STAFF_OPERATION,true,false),403); }
    @Test void inactiveDeveloperIsNeverUnmasked() { scope("일반 사용자(PM)",DataScope.PROJECT,"UI-02.read",true,false);AuthorizationTest.status(()->policy.require(Target.DEVELOPER,row,Purpose.STAFF_OPERATION,false,false),403); }
    @Test void generalDownloadDoesNotGrantOriginalContactDownload() { scope("일반 사용자(PM)",DataScope.PROJECT,"UI-02.download",true,false);AuthorizationTest.status(()->policy.require(Target.DEVELOPER,row,Purpose.STAFF_OPERATION,true,true),403); }
    @Test void wrongPurposeIsNotConvertedIntoContactPermission() { scope("일반 사용자(PM)",DataScope.PROJECT,"UI-02.read",true,false);AuthorizationTest.status(()->policy.require(Target.DEVELOPER,row,Purpose.ACCOUNT_CORRECTION,true,false),403); }
}
