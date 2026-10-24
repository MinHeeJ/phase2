package com.sicost.salespartsops;

import static com.sicost.salesparts.SalesPartModels.*;
import static com.sicost.salespartsops.SalesPartLifecycleModels.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.sicost.common.api.*;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.security.*;
import com.sicost.salesparts.*;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers=SalesPartLifecycleController.class)
@Import({RequestIdFilter.class,ApiExceptionHandler.class,SalesPartLifecycleService.class,SalesPartExportService.class,SalesPartService.class,ExcelExportService.class,CurrentUser.class,PermissionService.class,SalesPartDisableApiTest.Time.class})
class SalesPartDisableApiTest {
    @TestConfiguration static class Time {
        @Bean Clock clock() {return Clock.fixed(Instant.parse("2026-10-22T15:01:00Z"),ZoneId.of("Asia/Seoul"));}
    }
    @Autowired MockMvc mvc;
    @MockBean SalesPartLifecycleMapper mapper;
    @MockBean SalesPartMapper reads;

    @BeforeEach void setup() {
        when(mapper.lock(2,LocalDate.of(2026,10,23))).thenReturn(row("N","Y",0,0));
        when(reads.part(eq(2L),any())).thenReturn(row("N","N",0,0));
        when(reads.standards(eq(2L),anyString())).thenReturn(List.of());
    }
    PartRow row(String hq,String use,long people,long projects) {
        return new PartRow("2","영업 파트",hq,use,1,people,projects);
    }
    void unchanged() {
        verify(mapper,never()).state(any());
        verify(mapper,never()).audit(any());
        verify(mapper,never()).deleteScheduled(any());
        verify(mapper,never()).closePrevious(any());
        verify(mapper,never()).insertStandard(any());
    }
    @Test void anonymousDisableLocksUpdatesAuditsThenReadsCommittedProjection()throws Exception {
        mvc.perform(post("/api/sales-parts/2/disable"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.bsn_part_id").value("2"))
            .andExpect(jsonPath("$.data.use_yn").value("N"));
        var order=inOrder(mapper,reads);
        order.verify(mapper).lock(2,LocalDate.of(2026,10,23));
        order.verify(mapper).state(new PartValueWrite(2,"N",null));
        order.verify(mapper).audit(new SalesPartAudit("mst.bsn_part","2","DISABLE","use_yn","Y","N",null));
        order.verify(reads).part(2,LocalDate.of(2026,10,23));
        verify(mapper,never()).deleteScheduled(any());
    }
    @ParameterizedTest @CsvSource({"3,0","0,1","3,1"})
    void eachConnectionRuleRejectsWithCountsAndMoveGuidance(long people,long projects)throws Exception {
        when(mapper.lock(eq(2L),any())).thenReturn(row("N","Y",people,projects));
        mvc.perform(post("/api/sales-parts/2/disable"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("PART_IN_USE"))
            .andExpect(jsonPath("$.message").value("영업 파트에 소속 인력 "+people+"명, 진행 프로젝트 "+projects+"건이 있습니다. 먼저 다른 파트로 옮겨 주세요."));
        unchanged();verifyNoInteractions(reads);
    }
    @Test void headquartersLockPrecedesAlreadyDisabledNoop()throws Exception {
        when(mapper.lock(eq(2L),any())).thenReturn(row("Y","N",0,0));
        mvc.perform(post("/api/sales-parts/2/disable"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("HQ_PART_LOCKED"));
        unchanged();verifyNoInteractions(reads);
    }
    @Test void repeatedDisableSkipsUpdateAndAuditEvenWithExistingConnections()throws Exception {
        when(mapper.lock(eq(2L),any())).thenReturn(row("N","N",3,1));
        mvc.perform(post("/api/sales-parts/2/disable"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.use_yn").value("N"));
        unchanged();
    }
    @ParameterizedTest @ValueSource(strings={"999","0","-1","abc","9223372036854775808"})
    void nonexistentOrInvalidIdentifierCannotCreateAnyRows(String id)throws Exception {
        mvc.perform(post("/api/sales-parts/{id}/disable",id))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        unchanged();verifyNoInteractions(reads);
    }
}
