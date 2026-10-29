package com.sicost.projects;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.billingsops.*;
import com.sicost.common.api.*;
import com.sicost.common.costallocation.*;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.security.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.io.ByteArrayInputStream;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.web.servlet.MockMvc;
import static com.sicost.projects.CostProjectModels.*;
import static com.sicost.common.costallocation.CostAllocationModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers=CostProjectController.class)
@Import({RequestIdFilter.class,ApiExceptionHandler.class,CostProjectService.class,CostProjectExportService.class,
    CostAllocationService.class,CostAllocationEngine.class,BillingRevenueQuery.class,ExcelExportService.class,
    CurrentUser.class,CostProjectApiTest.Time.class})
class CostProjectApiTest {
    @TestConfiguration static class Time {
        @Bean Clock clock() {return Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"),ZoneId.of("Asia/Seoul"));}
    }
    @Autowired MockMvc mvc;
    @Autowired CostProjectService service;
    @Autowired ObjectMapper json;
    @MockBean CostProjectMapper mapper;
    @MockBean CostAllocationMapper allocations;
    @MockBean BillingMapper bills;
    @MockBean BillingReceivableService receivables;
    @MockBean PermissionService permissions;
    static BigDecimal n(String value) {return new BigDecimal(value);}
    Source source(String type,Long budget,BigDecimal target) {
        return new Source(17,"P-2617","분석 프로젝트",type,"RUN",1L,"영업","PM 미지정",500L,500L,budget,target,
            n("2.00"),n("1.00"),n("30"),n("50"),1);
    }
    void project(Source p) {
        when(mapper.source(any())).thenReturn(p);when(mapper.sources(any())).thenReturn(List.of(p));
    }
    @BeforeEach void setup() {
        project(source("TK",400L,n("20")));
        when(mapper.costRate(anyString())).thenReturn(n("80"));
        when(mapper.people(17)).thenReturn(List.of(new PersonName(7,"개발자")));
        when(allocations.project(17)).thenReturn(17L);
        when(allocations.people("2026-10")).thenReturn(List.of(new CostAllocationModels.Person(7,"FL","DIRECT",320L)));
        when(allocations.assignments("2026-10")).thenReturn(List.of(new Assignment(9,7,17,n("3.00"),"ACTIVE","TK","RUN","NEW",null,500L)));
        when(bills.strictProjectRevenue(any())).thenReturn(List.of(new BillingRevenueQuery.ProjectRevenue("2026-10",200,false)));
    }
    @Test void anonymousListUsesStoredBudgetConfirmedRevenueAndSharedEngine() throws Exception {
        mvc.perform(get("/api/costs/projects").param("year","2026"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.meta").isMap())
            .andExpect(jsonPath("$.data.items[0].total_cost_amt").value(320))
            .andExpect(jsonPath("$.data.items[0].confirmed_supply_amt").value(200))
            .andExpect(jsonPath("$.data.items[0].profit_amt").value(-120))
            .andExpect(jsonPath("$.data.items[0].budget_use_rt").value(80.0))
            .andExpect(jsonPath("$.data.items[0].budget_warning").value("WARNING"))
            .andExpect(jsonPath("$.data.items[0].expected_margin_rt").value(30))
            .andExpect(jsonPath("$.data.page").value(0)).andExpect(jsonPath("$.data.size").value(50));
        verify(permissions).can("UI-04.read");verify(bills,never()).revenue(anyInt());
        verify(mapper,never()).access(any());
    }
    @Test void projectCostOperationsUseDurableClasspathContract() throws Exception {
        try(var input=new org.springframework.core.io.ClassPathResource("contracts/openapi.yaml").getInputStream()) {
            Map<?,?> contract=new org.yaml.snakeyaml.Yaml().load(input);
            Map<?,?> paths=(Map<?,?>)contract.get("paths");
            Map<?,?> list=(Map<?,?>)((Map<?,?>)paths.get("/api/costs/projects")).get("get");
            assertThat(list.get("operationId")).isEqualTo("listCostProjects");
            assertThat(list.get("responses")).isInstanceOf(Map.class);
        }
    }
    @Test void detailMonthSumAndPeopleMatchListWithoutAddingFreeCostAgain() throws Exception {
        when(allocations.assignments("2026-10")).thenReturn(List.of(new Assignment(9,7,17,n("0.50"),"ACTIVE","TK","RUN","FREE",null,500L)));
        var detail=service.detail(17,service.query(Map.of("year","2026")));
        assertThat(detail.project().total_cost_amt()).isEqualTo(320);
        assertThat(detail.project().free_cost_amt()).isEqualTo(320);
        assertThat(detail.people_rows().get(0).personal_revenue_amt()).isZero();
        assertThat(detail.monthly_rows().stream().mapToLong(m->m.total_cost_amt()).sum()).isEqualTo(320);
        mvc.perform(get("/api/costs/projects/17").param("year","2026"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.people_rows[0].dev_id").value(7))
            .andExpect(jsonPath("$.data.referenceMonth").value("2026-10"));
    }
    @Test void internalHidesFinancialRevenueAndShowsRnd() throws Exception {
        project(source("IN",400L,n("20")));
        when(allocations.assignments("2026-10")).thenReturn(List.of(new Assignment(9,7,17,n("1.00"),"ACTIVE","IN","RUN","NEW",null,null)));
        mvc.perform(get("/api/costs/projects/17").param("year","2026"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.project.cur_cntrct_amt").isEmpty())
            .andExpect(jsonPath("$.data.project.confirmed_supply_amt").isEmpty())
            .andExpect(jsonPath("$.data.project.margin_rt").isEmpty()).andExpect(jsonPath("$.data.project.rnd_cost_amt").value(320));
    }
    @Test void missingTargetHasNoRateFallback() {
        project(source("TK",null,n("30")));
        var row=service.detail(17,service.query(Map.of("year","2026"))).project();
        assertThat(row.budget_use_rt()).isNull();assertThat(row.budget_warning()).isEqualTo("UNAVAILABLE");
    }
    @Test void nonTwentyTargetUsesStoredBudgetAndHundredPercentIsDanger() {
        project(source("TK",320L,n("36")));
        var row=service.detail(17,service.query(Map.of("year","2026"))).project();
        assertThat(row.budget_use_rt()).isEqualByComparingTo("100");assertThat(row.budget_warning()).isEqualTo("DANGER");
    }
    @Test void lowMarginIsStrictBeyondFivePointsNotRoundedEquality() {
        assertThat(CostProjectService.low(n("15"),n("20"))).isFalse();
        assertThat(CostProjectService.low(n("14.999"),n("20"))).isTrue();
        assertThat(CostProjectService.low(null,n("20"))).isFalse();
    }
    @Test void missingSalaryPropagatesNullAndWarning() {
        when(allocations.people("2026-10")).thenReturn(List.of(new CostAllocationModels.Person(7,"FL","DIRECT",null)));
        var row=service.detail(17,service.query(Map.of("year","2026"))).project();
        assertThat(row.total_cost_amt()).isNull();assertThat(row.margin_rt()).isNull();assertThat(row.warnings()).anyMatch(w->w.contains("급여"));
    }
    @Test void missingTkEstimateDoesNotMultiplyHeadcountByMmAndCallItMoney() {
        var p=source("TK",400L,n("20"));
        project(new Source(p.prj_id(),p.prj_no(),p.prj_nm(),p.cntrct_ty_cd(),p.prj_stts_cd(),p.bsn_part_id(),p.part_name(),p.owner(),
            p.init_cntrct_amt(),p.cur_cntrct_amt(),p.trgt_cst_amt(),p.trgt_mrgn_rt(),p.plan_mm_qty(),n("1"),null,p.actl_prgs_rt(),p.headcount()));
        assertThat(service.detail(17,service.query(Map.of("year","2026"))).project().expected_margin_rt()).isNull();
    }
    @Test void missingConfirmedTkDateIsWarningNotPlannedDateRevenue() {
        when(bills.strictProjectRevenue(any())).thenReturn(List.of(new BillingRevenueQuery.ProjectRevenue(null,200,true)));
        var detail=service.detail(17,service.query(Map.of("year","2026")));
        assertThat(detail.project().confirmed_supply_amt()).isNull();assertThat(detail.warnings()).contains("확정 청구일 누락");
    }
    @Test void causesUsePositivePriorityStableTiesAndRetainProjectYear() throws Exception {
        mvc.perform(get("/api/costs/projects/17/causes").param("year","2026"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.main_cause").value("EXCESS_MM"))
            .andExpect(jsonPath("$.data.items[0].amount").value(106))
            .andExpect(jsonPath("$.data.items[0].navigation").value("/UI-11?projectId=17&year=2026"));
        when(allocations.assignments("2026-10")).thenReturn(List.of(new Assignment(9,7,17,n("3"),"ACTIVE","TK","RUN","FREE",null,0L)));
        var causes=service.causes(17,service.query(Map.of("year","2026")));
        assertThat(causes.items().get(0).code()).isEqualTo("COST_PRICE");
        assertThat(causes.items().get(1).code()).isEqualTo("FREE_SUPPORT");
        assertThat(causes.items().get(0).available()).isFalse();assertThat(causes.items().get(0).navigation()).isNull();
        assertThat(causes.progress_excess_amt()).isEqualByComparingTo("120");
        assertThat(causes.unexplained_amt()).isEqualByComparingTo("-626");
    }
    @Test void progressExcessAndNegativeResidualMatchContractFiftyMinusSixty() {
        when(allocations.people("2026-10")).thenReturn(List.of(new CostAllocationModels.Person(7,"FL","DIRECT",250L)));
        when(allocations.assignments("2026-10")).thenReturn(List.of(new Assignment(9,7,17,n("2"),"ACTIVE","TK","RUN","NEW",null,0L)));
        when(mapper.costRate(anyString())).thenReturn(n("80"));
        // 250 - 237.5 * 80% = 60, with exact whole-won reference of 250 and rate 76%.
        when(allocations.assignments("2026-10")).thenReturn(List.of(new Assignment(9,7,17,n("2"),"ACTIVE","TK","RUN","NEW",null,250L)));
        when(mapper.costRate(anyString())).thenReturn(n("76"));
        var causes=service.causes(17,service.query(Map.of("year","2026")));
        assertThat(causes.progress_excess_amt()).isEqualByComparingTo("50");
        assertThat(causes.unexplained_amt()).isEqualByComparingTo("-10");
    }
    @Test void monthlyContractUsesReferenceMonthMarginNotAnnualMargin() {
        project(source("MO",400L,n("20")));
        when(bills.strictProjectRevenue(any())).thenReturn(List.of(new BillingRevenueQuery.ProjectRevenue("2026-09",1000,false),new BillingRevenueQuery.ProjectRevenue("2026-10",400,false)));
        var row=service.detail(17,service.query(Map.of("year","2026"))).project();
        assertThat(row.monthly_margin_rt()).isEqualByComparingTo("20");assertThat(row.margin_rt()).isNotEqualByComparingTo("20");
    }
    @Test void closedMissingSnapshotIsConflictForListDetailAndCauses() throws Exception {
        when(allocations.monthStatus("2026-10")).thenReturn("CLOSED");when(allocations.missingSnapshots(any())).thenReturn(1L);
        for(String suffix:List.of("","/17","/17/causes"))mvc.perform(get("/api/costs/projects"+suffix).param("year","2026"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH"));
        verify(allocations,never()).people("2026-10");
    }
    @Test void simulationTkIsArithmeticAndDoesNotQueryAllocationOrWrite() throws Exception {
        mvc.perform(post("/api/costs/projects/17/simulate").contentType("application/json")
            .content("{\"headcount_delta\":1,\"monthly_cost_amt\":10,\"months\":3}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.simulated").value(true))
            .andExpect(jsonPath("$.data.cost_delta_amt").value(30)).andExpect(jsonPath("$.data.revenue_delta_amt").value(0))
            .andExpect(jsonPath("$.data.profit_delta_amt").value(-30));
        verifyNoInteractions(allocations,bills);verify(mapper,never()).access(any());
    }
    @Test void simulationMoAllowsReductionWithPriceAndRealStaffing() throws Exception {
        project(source("MO",400L,n("20")));
        mvc.perform(post("/api/costs/projects/17/simulate").contentType("application/json")
            .content("{\"headcount_delta\":-1,\"monthly_cost_amt\":10,\"months\":3,\"monthly_price_amt\":15}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.cost_delta_amt").value(-30))
            .andExpect(jsonPath("$.data.revenue_delta_amt").value(-45)).andExpect(jsonPath("$.data.profit_delta_amt").value(-15));
    }
    @ParameterizedTest @ValueSource(strings={"{}","{","null","[]","{\"headcount_delta\":1.2}","{\"unknown\":1}","{\"headcount_delta\":1} {}"})
    void malformedSimulationReturnsFeatureValidation(String body) throws Exception {
        mvc.perform(post("/api/costs/projects/17/simulate").contentType("application/json").content(body))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.requestId").isNotEmpty());
    }
    @ParameterizedTest @CsvSource({"-2,10,1,headcount_delta","1,-1,1,monthly_cost_amt","1,10,0,months","2147483647,9223372036854775807,2,monthly_cost_amt"})
    void invalidSimulationRule(int head,long cost,int months,String field) throws Exception {
        mvc.perform(post("/api/costs/projects/17/simulate").contentType("application/json").content(json.writeValueAsString(Map.of(
            "headcount_delta",head,"monthly_cost_amt",cost,"months",months))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field));
    }
    @Test void simulationMoRequiresPriceAndTkExcludesIt() throws Exception {
        project(source("MO",400L,n("20")));
        mvc.perform(post("/api/costs/projects/17/simulate").contentType("application/json").content("{\"headcount_delta\":1,\"monthly_cost_amt\":1,\"months\":1}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("monthly_price_amt"));
        project(source("TK",400L,n("20")));
        mvc.perform(post("/api/costs/projects/17/simulate").contentType("application/json").content("{\"headcount_delta\":1,\"monthly_cost_amt\":1,\"months\":1,\"monthly_price_amt\":1}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("monthly_price_amt"));
    }
    @ParameterizedTest @CsvSource({"year,0","year,2027","year,abc","type,XX","status,BAD","partId,0","size,30","page,-1","sort,'total_cost_amt,ASC'"})
    void invalidListField(String field,String value) throws Exception {
        var request=get("/api/costs/projects").param("year","2026");request.param(field,value);
        if(field.equals("year"))request=get("/api/costs/projects").param(field,value);
        mvc.perform(request).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.field").value(field));
    }
    @Test void missingYearIsValidation() throws Exception {
        mvc.perform(get("/api/costs/projects")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("year"));
    }
    @Test void unknownProjectIs404ForDetailCausesAndSimulation() throws Exception {
        when(mapper.source(any())).thenReturn(null);
        mvc.perform(get("/api/costs/projects/17").param("year","2026"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(get("/api/costs/projects/17/causes").param("year","2026"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(post("/api/costs/projects/17/simulate").contentType("application/json").content("{}"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        verifyNoInteractions(allocations,bills);
    }
    @Test void emptyListStillHasTypedMetadataAndSummary() throws Exception {
        when(mapper.sources(any())).thenReturn(List.of());
        mvc.perform(get("/api/costs/projects").param("year","2026"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0)).andExpect(jsonPath("$.data.items").isEmpty())
            .andExpect(jsonPath("$.data.summary.project_count").value(0));
    }
    @ParameterizedTest @ValueSource(strings={"0","-1","abc","01","9223372036854775808"})
    void invalidIdentityIsFeatureValidationForEveryDetailOperation(String id) throws Exception {
        for(String suffix:List.of("","/causes"))mvc.perform(get("/api/costs/projects/"+id+suffix).param("year","2026"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("id"));
        mvc.perform(post("/api/costs/projects/"+id+"/simulate").contentType("application/json").content("{}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("id"));
    }
    @Test void closedSnapshotSuccessNeverLoadsCurrentInputs() throws Exception {
        when(allocations.monthStatus("2026-10")).thenReturn("CLOSED");
        when(allocations.snapshots(any())).thenReturn(List.of(new Allocation("2026-10",7,17L,9L,"PROJECT",n("1"),n("1"),n("1"),40L,5,100L,null,"N","N","N")));
        mvc.perform(get("/api/costs/projects/17").param("year","2026"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.project.total_cost_amt").value(45));
        verify(allocations,never()).people("2026-10");
    }
    @Test void paginationAndNumericTieBreakDoNotChangeFullFilteredSummary() throws Exception {
        var p=source("TK",400L,n("20"));
        var second=new Source(18,p.prj_no(),p.prj_nm(),p.cntrct_ty_cd(),p.prj_stts_cd(),p.bsn_part_id(),p.part_name(),p.owner(),
            p.init_cntrct_amt(),p.cur_cntrct_amt(),p.trgt_cst_amt(),p.trgt_mrgn_rt(),p.plan_mm_qty(),p.remaining_mm_qty(),p.remaining_cost_amt(),p.actl_prgs_rt(),p.headcount());
        when(allocations.project(18)).thenReturn(18L);when(mapper.sources(any())).thenReturn(List.of(second,p));
        var first=service.list(service.query(Map.of("year","2026","sort","prj_nm,desc")));
        assertThat(first.items()).extracting(Row::prj_id).containsExactly(17L,18L);
        var offpage=service.list(service.query(Map.of("year","2026","page","1")));
        assertThat(offpage.total()).isEqualTo(2);assertThat(offpage.items()).isEmpty();assertThat(offpage.summary()).isEqualTo(first.summary());
    }
    @Test void workbookValidationFailureKeepsJsonErrorEnvelope() throws Exception {
        mvc.perform(get("/api/costs/projects").accept(DownloadArtifact.XLSX))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentType("application/json"))
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.field").value("year"))
            .andExpect(jsonPath("$.requestId").isNotEmpty());
        verify(mapper, never()).access(any());
    }
    @Test void xlsxNegotiationProducesRealWorkbookAndExactlyOneNullActorAudit() throws Exception {
        var response=mvc.perform(get("/api/costs/projects").param("year","2026").param("page","1").accept(DownloadArtifact.XLSX))
            .andExpect(status().isOk()).andExpect(content().contentType(DownloadArtifact.XLSX)).andReturn().getResponse();
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            assertThat(book.getSheetAt(0).getRow(4).getCell(8).getNumericCellValue()).isEqualTo(320);
        }
        verify(mapper).access(argThat(a->a.actor()==null && a.detail().contains("\"rowCount\":1")));
        verify(permissions).can("UI-04.download");
    }
}
