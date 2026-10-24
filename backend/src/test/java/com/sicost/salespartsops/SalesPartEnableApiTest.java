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
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers=SalesPartLifecycleController.class)
@Import({RequestIdFilter.class,ApiExceptionHandler.class,SalesPartLifecycleService.class,SalesPartExportService.class,SalesPartService.class,ExcelExportService.class,CurrentUser.class,PermissionService.class,SalesPartEnableApiTest.Time.class})
class SalesPartEnableApiTest {
    @TestConfiguration static class Time {
        @Bean Clock clock() {return Clock.fixed(Instant.parse("2026-10-22T15:01:00Z"),ZoneId.of("Asia/Seoul"));}
    }
    @Autowired MockMvc mvc;
    @MockBean SalesPartLifecycleMapper mapper;
    @MockBean SalesPartMapper reads;
    final LocalDate today=LocalDate.of(2026,10,23);
    final SalesPartStandard current=new SalesPartStandard("20","2026-01",null,"파트장",new BigDecimal("40.00"),1500000,"적용 중");

    PartRow row(String use) {return new PartRow("2","영업 파트","N",use,1,3,1);}
    @BeforeEach void setup() {
        when(mapper.lock(2,today)).thenReturn(row("N"));
        when(reads.part(2,today)).thenReturn(row("Y"));
        when(reads.standards(2,"2026-10")).thenReturn(List.of(current));
    }
    void noMutation() {
        verify(mapper,never()).state(any());
        verify(mapper,never()).audit(any());
    }
    @Test void anonymousEnableReturnsOriginalIdentityAndStandardAndAuditsOneTransition()throws Exception {
        mvc.perform(post("/api/sales-parts/2/enable"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.meta").isMap())
            .andExpect(jsonPath("$.data.bsn_part_id").value("2"))
            .andExpect(jsonPath("$.data.bsn_part_nm").value("영업 파트"))
            .andExpect(jsonPath("$.data.use_yn").value("Y"))
            .andExpect(jsonPath("$.data.peopleCount").value(3))
            .andExpect(jsonPath("$.data.projectCount").value(1))
            .andExpect(jsonPath("$.data.standards[0].part_std_hist_id").value("20"))
            .andExpect(jsonPath("$.data.currentStandard.prft_dstrb_rt").value(40));
        var order=inOrder(mapper,reads);
        order.verify(mapper).lock(2,today);
        order.verify(mapper).state(new PartValueWrite(2,"Y",null));
        order.verify(mapper).audit(new SalesPartAudit("mst.bsn_part","2","UPDATE","use_yn","N","Y",null));
        order.verify(reads).part(2,today);
        verify(mapper,never()).rename(any());
        verify(mapper,never()).deleteScheduled(any());
        verify(mapper,never()).closePrevious(any());
        verify(mapper,never()).insertStandard(any());
        verify(reads,never()).insertPart(any());
        verify(reads,never()).insertStandard(any());
    }
    @Test void enabledPartReturnsToPublicOptions()throws Exception {
        when(mapper.options()).thenReturn(List.of(),List.of(new SalesPartOption("2","영업 파트",false)));
        mvc.perform(get("/api/sales-parts/options"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(post("/api/sales-parts/2/enable")).andExpect(status().isOk());
        mvc.perform(get("/api/sales-parts/options"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value("2"))
            .andExpect(jsonPath("$.data[0].name").value("영업 파트"))
            .andExpect(jsonPath("$.data[0].hq").value(false));
        verify(mapper).state(new PartValueWrite(2,"Y",null));
    }
    @Test void alreadyEnabledDoesNotUpdateTimestampOrAppendHistory()throws Exception {
        when(mapper.lock(2,today)).thenReturn(row("Y"));
        mvc.perform(post("/api/sales-parts/2/enable"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.use_yn").value("Y"));
        noMutation();
    }
    @ParameterizedTest @ValueSource(strings={"999","0","-1","abc","02","9223372036854775808"})
    void missingOrMalformedPartNeverCreatesOrUpdatesData(String id)throws Exception {
        mvc.perform(post("/api/sales-parts/{id}/enable",id))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
            .andExpect(jsonPath("$.requestId").isNotEmpty());
        noMutation();verifyNoInteractions(reads);
    }
}
