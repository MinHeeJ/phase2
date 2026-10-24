package com.sicost.partneractivation;

import static com.sicost.partnersops.PartnerLifecycleModels.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.sicost.common.api.*;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.security.*;
import com.sicost.partnersops.*;
import java.time.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers=PartnerLifecycleController.class)
@Import({RequestIdFilter.class,ApiExceptionHandler.class,PartnerLifecycleService.class,ExcelExportService.class,
    CurrentUser.class,PermissionService.class,PartnerEnableApiTest.Time.class})
class PartnerEnableApiTest {
    @TestConfiguration static class Time {
        @Bean Clock clock(){return Clock.fixed(Instant.parse("2026-10-22T15:01:00Z"),ZoneId.of("Asia/Seoul"));}
    }
    @Autowired MockMvc mvc;
    @MockBean PartnerLifecycleMapper mapper;

    PartnerDetail detail(String use) {
        return new PartnerDetail("41","C-041","CUST","고객사 A","123-45-67890",null,null,null,null,null,
            "tax@company.co.kr",null,null,null,use);
    }
    @Test void anonymousEnableReturnsSameIdentityAndWritesExactlyOneUpdateAudit()throws Exception {
        when(mapper.lock("41")).thenReturn(detail("N"));
        when(mapper.detail("41")).thenReturn(detail("Y"));
        mvc.perform(post("/api/partners/41/enable"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.meta").isMap()).andExpect(jsonPath("$.data.use_yn").value("Y"))
            .andExpect(jsonPath("$.data.ptnr_id").value("41"))
            .andExpect(jsonPath("$.data.ptnr_no").value("C-041"))
            .andExpect(jsonPath("$.data.biz_reg_no").value("123-45-67890"))
            .andExpect(jsonPath("$.data.bank_cd").doesNotExist());
        var order=inOrder(mapper);
        order.verify(mapper).lock("41");
        order.verify(mapper).changeState(new PartnerStateWrite("41","Y",null));
        order.verify(mapper).audit(new PartnerAudit("41","UPDATE","N","Y",null));
        order.verify(mapper).detail("41");
        verifyNoMoreInteractions(mapper);
    }
    @Test void alreadyEnabledReturnsCurrentDetailWithoutUpdateOrAudit()throws Exception {
        when(mapper.lock("41")).thenReturn(detail("Y"));
        when(mapper.detail("41")).thenReturn(detail("Y"));
        mvc.perform(post("/api/partners/41/enable"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.use_yn").value("Y"));
        verify(mapper,never()).changeState(any());verify(mapper,never()).audit(any());
    }
    @Test void missingPartnerIs404AndCannotCreateOrAudit()throws Exception {
        mvc.perform(post("/api/partners/99/enable"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.requestId").isNotEmpty());
        verify(mapper).lock("99");verifyNoMoreInteractions(mapper);
    }
    @ParameterizedTest @ValueSource(strings={"0","-1","abc","01","9223372036854775808"})
    void invalidIdentifierIs400BeforeDatabaseAccess(String id)throws Exception {
        mvc.perform(post("/api/partners/"+id+"/enable"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("id"));
        verifyNoInteractions(mapper);
    }
    @Test void databaseErrorDoesNotLeakSqlOrReportSuccess()throws Exception {
        when(mapper.lock("41")).thenReturn(detail("N"));
        doThrow(new IllegalStateException("private database details")).when(mapper).audit(any());
        mvc.perform(post("/api/partners/41/enable"))
            .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.message").value("서버 오류가 발생했습니다."));
        verify(mapper,never()).detail(anyString());
    }
}
