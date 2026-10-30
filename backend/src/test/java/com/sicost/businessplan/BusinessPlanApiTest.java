package com.sicost.businessplan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import static com.sicost.businessplan.BusinessPlanModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers=BusinessPlanController.class)
@Import({RequestIdFilter.class,ApiExceptionHandler.class,BusinessPlanService.class,CurrentUser.class,PermissionService.class})
class BusinessPlanApiTest {
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean BusinessPlanMapper mapper;
    @MockBean BusinessPlanComparisonService comparisons;
    @MockBean Clock clock;
    final List<Plan> rows=new ArrayList<>();
    @BeforeEach void setup() {
        rows.clear();rows.add(plan(10,100));
        when(clock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(clock.withZone(any())).thenReturn(clock);
        when(clock.instant()).thenReturn(Instant.parse("2026-10-30T00:00:00Z"));
        when(mapper.list(2026)).thenAnswer(i->List.copyOf(rows));
        when(mapper.parts()).thenReturn(List.of(new Part(7,"영업파트",true)));
        when(mapper.closedMonths(2026)).thenReturn(List.of("2026-09"));
        when(mapper.upsert(any())).thenAnswer(i->{var w=i.getArgument(0,PlanWrite.class);rows.removeIf(p->p.month()==w.input().month());rows.add(w.input());return 1L;});
    }
    Plan plan(int month,long revenue) {return new Plan(2026,month,7L,revenue,-10,new BigDecimal("80.0"));}
    String body(Plan... plans)throws Exception{return json.writeValueAsString(Map.of("items",List.of(plans)));}
    @Test void publicReadReturnsDatabaseRowsAndDoesNotWrite()throws Exception {
        mvc.perform(get("/api/finance/business-plan?year=2026")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].partId").value(7)).andExpect(jsonPath("$.data.items[0].target_revenue").value(100));
        verify(mapper,never()).upsert(any());verify(mapper,never()).audit(any());
    }
    @Test void publicSaveKeepsYearAndAuditsAllChangedTargets()throws Exception {
        mvc.perform(put("/api/finance/business-plan").param("year","2026").contentType("application/json").content(body(plan(10,200))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].year").value(2026))
            .andExpect(jsonPath("$.data.items[0].target_revenue").value(200));
        var audit=org.mockito.ArgumentCaptor.forClass(PlanAudit.class);verify(mapper).audit(audit.capture());
        assertThat(json.readTree(audit.getValue().before()).get("target_revenue").asLong()).isEqualTo(100);
        assertThat(json.readTree(audit.getValue().after()).get("target_revenue").asLong()).isEqualTo(200);
    }
    @Test void closedChangeRejectsWholeBatchBeforeOpenWrite()throws Exception {
        rows.add(plan(9,100));
        mvc.perform(put("/api/finance/business-plan?year=2026").contentType("application/json").content(body(plan(10,300),plan(9,200))))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH"));
        verify(mapper,never()).upsert(any());assertThat(rows.get(0).target_revenue()).isEqualTo(100);
    }
    @Test void unchangedClosedAndOmittedClosedArePreserved()throws Exception {
        rows.add(plan(9,100));
        mvc.perform(put("/api/finance/business-plan?year=2026").contentType("application/json").content(body(plan(9,100),plan(11,0))))
            .andExpect(status().isOk());verify(mapper,times(1)).upsert(any());assertThat(rows).contains(plan(9,100));
    }
    @Test void duplicateNaturalKeyRejected()throws Exception {
        mvc.perform(put("/api/finance/business-plan?year=2026").contentType("application/json").content(body(plan(10,100),plan(10,200))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));verify(mapper,never()).upsert(any());
    }
    @ParameterizedTest @ValueSource(strings={"year","month","partId","target_revenue","target_operating_profit","target_utilization_rate"})
    void missingRequiredFieldIsFinanceValidation(String field)throws Exception {
        var item=json.valueToTree(plan(10,100));((com.fasterxml.jackson.databind.node.ObjectNode)item).remove(field);
        mvc.perform(put("/api/finance/business-plan?year=2026").contentType("application/json").content("{\"items\":["+item+"]}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.field").value(field));verify(mapper,never()).upsert(any());
    }
    @Test void yearMismatchDoesNotChangeHeaderOrDetail()throws Exception {
        var other=new Plan(2027,10,7L,100,10,BigDecimal.TEN);
        mvc.perform(put("/api/finance/business-plan?year=2026").contentType("application/json").content(body(other)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("year"));verify(mapper,never()).upsert(any());
    }
    @Test void unknownPartIsNotFound()throws Exception {
        mvc.perform(put("/api/finance/business-plan?year=2026").contentType("application/json")
            .content(body(new Plan(2026,10,99L,10,0,BigDecimal.TEN))))
            .andExpect(status().isNotFound());verify(mapper,never()).upsert(any());
    }
    @Test void malformedBodyIsFinanceValidation()throws Exception {
        mvc.perform(put("/api/finance/business-plan?year=2026").contentType("application/json").content("{"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
    @Test void nullPartViolatesApprovedRequiredIntegerContract()throws Exception {
        mvc.perform(put("/api/finance/business-plan?year=2026").contentType("application/json")
            .content(body(new Plan(2026,10,null,100,0,BigDecimal.TEN))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("partId"));
        verify(mapper,never()).upsert(any());
    }
    @ParameterizedTest @ValueSource(strings={"-1","1.5","9223372036854775808","\"100\""})
    void invalidRevenueIsRejectedWithoutAudit(String value)throws Exception {
        var input=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(plan(10,100));
        input.set("target_revenue",json.readTree(value));
        mvc.perform(put("/api/finance/business-plan?year=2026").contentType("application/json").content("{\"items\":["+input+"]}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("target_revenue"));
        verify(mapper,never()).upsert(any());verify(mapper,never()).audit(any());
    }
    @ParameterizedTest @ValueSource(strings={"-1","100.01","80.123","null","\"80\""})
    void invalidUtilizationIsRejected(String value)throws Exception {
        var input=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(plan(10,100));
        input.set("target_utilization_rate",json.readTree(value));
        mvc.perform(put("/api/finance/business-plan?year=2026").contentType("application/json").content("{\"items\":["+input+"]}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("target_utilization_rate"));
        verify(mapper,never()).upsert(any());
    }
    @Test void futurePlanMonthIsAllowedAndZeroIsStored()throws Exception {
        mvc.perform(put("/api/finance/business-plan?year=2026").contentType("application/json").content(body(plan(12,0))))
            .andExpect(status().isOk());verify(mapper).upsert(argThat(w->w.planMonth().equals("2026-12")&&w.input().target_revenue()==0));
    }
    @Test void inactivePartCannotAcquireNewTargets()throws Exception {
        when(mapper.parts()).thenReturn(List.of(new Part(7,"사용 안 함",false)));
        mvc.perform(put("/api/finance/business-plan?year=2026").contentType("application/json").content(body(plan(11,100))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("partId"));
        verify(mapper,never()).upsert(any());
    }
    @Test void pastYearIsReadOnly()throws Exception {
        mvc.perform(put("/api/finance/business-plan?year=2025").contentType("application/json")
            .content(body(new Plan(2025,10,7L,100,0,BigDecimal.TEN))))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_MONTH"));
        verify(mapper,never()).upsert(any());
    }
    @ParameterizedTest @ValueSource(strings={"", "?year=no", "?year=0", "?year=10000"})
    void invalidReadYear(String query)throws Exception {
        mvc.perform(get("/api/finance/business-plan"+query)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
    @Test void unexpectedReadErrorIsSafeFinanceError()throws Exception {
        when(mapper.list(2026)).thenThrow(new IllegalStateException("private connection details"));
        mvc.perform(get("/api/finance/business-plan?year=2026")).andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR")).andExpect(jsonPath("$.message").value("서버 오류가 발생했습니다."));
    }
    @Test void comparisonDelegatesApprovedQuery()throws Exception {
        when(comparisons.compare(2026,10,7L)).thenReturn(new Comparison(List.of(plan(10,100)),List.of(),null,List.of(),null,null));
        mvc.perform(get("/api/finance/business-plan/comparison").param("year","2026").param("month","10").param("partId","7"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].month").value(10));
        verify(comparisons).compare(2026,10,7L);
    }
    @ParameterizedTest @ValueSource(strings={"year=2026&month=0","year=2026&month=13","year=2026&month=x","year=2026&month=10&partId=x"})
    void comparisonRejectsInvalidParameters(String query)throws Exception {
        mvc.perform(get("/api/finance/business-plan/comparison?"+query)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));verifyNoInteractions(comparisons);
    }
}
