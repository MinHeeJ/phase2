package com.sicost.partnersops;

import static com.sicost.partnersops.PartnerLifecycleModels.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.security.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.*;

@WebMvcTest(controllers=PartnerLifecycleController.class)
@Import({RequestIdFilter.class,ApiExceptionHandler.class,PartnerLifecycleService.class,ExcelExportService.class,
    CurrentUser.class,PermissionService.class,PartnerDisableApiTest.Time.class})
class PartnerDisableApiTest {
    @TestConfiguration static class Time {
        @Bean Clock clock(){return Clock.fixed(Instant.parse("2026-10-22T15:01:00Z"),ZoneId.of("Asia/Seoul"));}
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean PartnerLifecycleMapper mapper;
    PartnerDetail row(String id,String use){return new PartnerDetail(id,"C-"+id,"BOTH","거래처 "+id,
        "123-45-67890",null,null,null,null,null,"tax@example.test","KB","12345678","예금주",use);}
    @BeforeEach void setup(){
        for(String id:List.of("1","2","3")) {
            when(mapper.lock(id)).thenReturn(row(id,"Y"));
            when(mapper.detail(id)).thenReturn(row(id,"Y"));
            when(mapper.block(new PartnerDateQuery(id,LocalDate.of(2026,10,23))))
                .thenReturn(new PartnerBlock(id,"거래처 "+id,0,0,""));
        }
    }
    ResultActions disable(String... ids)throws Exception {
        return mvc.perform(post("/api/partners/disable").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("ids",ids))));
    }
    void blocked(String id,long projects,long people){
        when(mapper.block(new PartnerDateQuery(id,LocalDate.of(2026,10,23))))
            .thenReturn(new PartnerBlock(id,"거래처 "+id,projects,people,""));
    }
    void noWrites(){verify(mapper,never()).changeState(any());verify(mapper,never()).audit(any());}

    @Test void publicDisableTransitionsEachPartnerAndAppendsOneAudit()throws Exception {
        disable("1","3").andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.disabledIds[0]").value("1"))
            .andExpect(jsonPath("$.data.disabledIds[1]").value("3"))
            .andExpect(jsonPath("$.data.blocked").isEmpty());
        for(String id:List.of("1","3")) {
            verify(mapper).changeState(new PartnerStateWrite(id,"N",null));
            verify(mapper).audit(new PartnerAudit(id,"DISABLE","Y","N",null));
        }
    }
    @ParameterizedTest @ValueSource(strings={"{}","{\"ids\":[]}","{\"ids\":null}","{\"ids\":[null]}",
        "{\"ids\":[1]}","{\"ids\":[\"0\"]}","{\"ids\":[\"-1\"]}","{\"ids\":[\"abc\"]}",
        "{\"ids\":[\"9223372036854775808\"]}","{\"ids\":[\"1\"],\"use_yn\":\"N\"}"})
    void malformedSelectionIs400AndNeverWrites(String body)throws Exception {
        mvc.perform(post("/api/partners/disable").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("ids"));
        noWrites();verify(mapper,never()).lock(anyString());
    }
    @Test void missingTargetValidatesAllExistenceBeforeAnyBlockingOrMutation()throws Exception {
        disable("1","2","99").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        noWrites();verify(mapper,never()).block(any());
    }
    @Test void projectRuleRejectsSinglePartner()throws Exception {
        blocked("1",2,0);disable("1").andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PARTNER_IN_USE"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("2건")));
        noWrites();
    }
    @Test void peopleRuleRejectsSinglePartner()throws Exception {
        blocked("2",0,3);disable("2").andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PARTNER_IN_USE"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("3명")));
        noWrites();
    }
    @Test void duplicateSingleBlockedSelectionStillUsesSingleConflict()throws Exception {
        blocked("2",0,3);disable("2","2").andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PARTNER_IN_USE"));noWrites();verify(mapper).lock("2");
    }
    @Test void mixedBatchWritesOnlyTwoAllowedAndReturnsExactBlockedCounts()throws Exception {
        blocked("2",0,3);disable("3","2","1").andExpect(status().isOk())
            .andExpect(jsonPath("$.data.disabledIds.length()").value(2))
            .andExpect(jsonPath("$.data.blocked[0].id").value("2"))
            .andExpect(jsonPath("$.data.blocked[0].name").value("거래처 2"))
            .andExpect(jsonPath("$.data.blocked[0].projectCount").value(0))
            .andExpect(jsonPath("$.data.blocked[0].peopleCount").value(3));
        verify(mapper,never()).changeState(argThat(w->w.id().equals("2")));
        verify(mapper,never()).audit(argThat(w->w.id().equals("2")));
        verify(mapper,times(2)).audit(any());
        var order=inOrder(mapper);order.verify(mapper).lock("1");order.verify(mapper).lock("2");order.verify(mapper).lock("3");
    }
    @Test void allBlockedBatchIs200WithReasonsAndNoWrites()throws Exception {
        blocked("1",2,0);blocked("2",0,3);disable("1","2").andExpect(status().isOk())
            .andExpect(jsonPath("$.data.disabledIds").isEmpty()).andExpect(jsonPath("$.data.blocked.length()").value(2));noWrites();
    }
    @Test void stoppedPartnersAreSkippedBeforeCountsAndDuplicatesAuditOnce()throws Exception {
        when(mapper.lock("2")).thenReturn(row("2","N"));
        disable("2","1","1").andExpect(status().isOk()).andExpect(jsonPath("$.data.disabledIds.length()").value(1));
        verify(mapper).lock("1");verify(mapper,never()).block(new PartnerDateQuery("2",LocalDate.of(2026,10,23)));
        verify(mapper).changeState(new PartnerStateWrite("1","N",null));verify(mapper,times(1)).audit(any());
    }
    @Test void stoppedOnlyDoesNotTouchUpdateTimestampOrHistory()throws Exception {
        when(mapper.lock("1")).thenReturn(row("1","N"));disable("1").andExpect(status().isOk())
            .andExpect(jsonPath("$.data.disabledIds").isEmpty()).andExpect(jsonPath("$.data.blocked").isEmpty());noWrites();
    }
    @Test void enableUsesSelectedIdentityAndCurrentDetail()throws Exception {
        when(mapper.lock("1")).thenReturn(row("1","N"));
        mvc.perform(post("/api/partners/1/enable")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.ptnr_id").value("1")).andExpect(jsonPath("$.data.use_yn").value("Y"));
        verify(mapper).changeState(new PartnerStateWrite("1","Y",null));
        verify(mapper).audit(new PartnerAudit("1","UPDATE","N","Y",null));
    }
    @Test void enableRepeatedTargetIsNoop()throws Exception {mvc.perform(post("/api/partners/1/enable")).andExpect(status().isOk());noWrites();}
    @Test void enableMissingTargetDoesNotCreate()throws Exception {mvc.perform(post("/api/partners/99/enable")).andExpect(status().isNotFound());noWrites();}
    @Test void enableMalformedIdentityIs400()throws Exception {mvc.perform(post("/api/partners/abc/enable")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("id"));noWrites();}
    @Test void optionsArePublicAndReadOnly()throws Exception {
        when(mapper.options("CUST")).thenReturn(List.of(new PartnerOption("1","C-001","고객 거래처")));
        mvc.perform(get("/api/partners/options").param("kind","CUST")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].id").value("1")).andExpect(jsonPath("$.data[0].name").value("고객 거래처"));noWrites();
    }
    @Test void optionsCanBeEmpty()throws Exception {when(mapper.options("VEND")).thenReturn(List.of());mvc.perform(get("/api/partners/options").param("kind","VEND")).andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());noWrites();}
    @Test void optionsInvalidKindIs400()throws Exception {mvc.perform(get("/api/partners/options").param("kind","BOTH")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("kind"));noWrites();}
}
