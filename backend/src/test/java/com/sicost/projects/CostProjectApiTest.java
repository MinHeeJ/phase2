package com.sicost.projects;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.billingsops.BillingRevenueQuery;
import com.sicost.billingsops.BillingModels.ProjectRevenue;
import com.sicost.common.api.*;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.security.*;
import com.sicost.costallocation.CostAllocationService;
import com.sicost.costallocation.CostAllocationModels.*;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import static com.sicost.projects.CostProjectModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers=CostProjectController.class)
@Import({RequestIdFilter.class,ApiExceptionHandler.class,CostProjectService.class,
    CostProjectExportService.class,ExcelExportService.class,CurrentUser.class,PermissionService.class})
class CostProjectApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean CostProjectMapper mapper;
    @MockBean CostAllocationService allocations;
    @MockBean BillingRevenueQuery revenue;
    @MockBean Clock clock;
    static BigDecimal n(String value) { return new BigDecimal(value); }
    static Source source(String type,Long budget,String goal) {
        return new Source(7,"P-2607","원가 분석",type,500L,500L,budget,n(goal),
            LocalDate.of(2026,10,31),"담당자",n("50"));
    }
    AllocationRow allocation(String month,long cost,long sales,String free) {
        return new AllocationRow(month,9L,11L,7L,"PROJECT",n("1.00"),n("1.00"),BigDecimal.ONE,
            cost,0,sales,500L,"N",free,"N");
    }
    void project(Source source,long cost,long sales,String free) {
        when(mapper.source(7)).thenReturn(source);
        when(mapper.sources(any())).thenReturn(List.of(source));
        when(revenue.projectRevenue(any())).thenReturn(List.of(new ProjectRevenue(7,"2026-10",sales)));
        when(allocations.year(2026,7L)).thenReturn(List.of(new Allocation("2026-10","2026-10",false,"원",List.of(),
            List.of(allocation("2026-10",cost,sales,free)),0)));
    }
    @BeforeEach void setup() {
        Clock fixed=Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"),ZoneId.of("Asia/Seoul"));
        when(clock.instant()).thenReturn(fixed.instant());
        when(clock.getZone()).thenReturn(fixed.getZone());
        when(clock.withZone(any())).thenAnswer(i->fixed.withZone(i.getArgument(0)));
        when(mapper.parameter(anyString(),anyString())).thenAnswer(i->switch(i.getArgument(0,String.class)) {
            case "TARGET_MARGIN_RATE" -> n("20"); case "LOW_MARGIN_GAP" -> n("5"); default -> n("80");
        });
        when(mapper.plans(any())).thenReturn(List.of(new Plan("2026-10",n("0.50"))));
        when(mapper.labor(any())).thenReturn(List.of(new Labor(9,"개발자",320L,null)));
        when(mapper.headcount(any())).thenReturn(1L);
        project(source("TK",400L,"20"),320,200,"N");
    }
    @Test void annualListMatchesDetailAndBudgetExampleWithoutAddingReferenceRevenue() throws Exception {
        mvc.perform(get("/api/costs/projects").param("year","2026").accept("application/json"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].budget_use_rt").value(80))
            .andExpect(jsonPath("$.data.items[0].reference_revenue_amt").value(400))
            .andExpect(jsonPath("$.data.items[0].confirmed_supply_amt").value(200))
            .andExpect(jsonPath("$.data.summary.profit_amt").value(-120))
            .andExpect(jsonPath("$.data.total").value(1));
        mvc.perform(get("/api/costs/projects/7").param("year","2026"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.project.cumulative_cost_amt").value(320))
            .andExpect(jsonPath("$.data.months[0].cumulative_cost_amt").value(320))
            .andExpect(jsonPath("$.data.people[0].allocation.dev_id").value(9));
        verify(mapper,never()).download(any());
    }
    @Test void causesExposeThreeRulesSignedResidualAndStableOwner() throws Exception {
        mvc.perform(get("/api/costs/projects/7/causes").param("year","2026"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(3))
            .andExpect(jsonPath("$.data.items[0].cause_code").value("OVER_MM"))
            .andExpect(jsonPath("$.data.items[0].owner_name").value("담당자"))
            .andExpect(jsonPath("$.data.progress_excess_amt").value(120))
            .andExpect(jsonPath("$.data.unexplained_amt").value(-200));
    }
    @Test void freeSupportIsNotAddedToTotalTwice() throws Exception {
        project(source("MO",400L,"20"),320,500,"Y");
        mvc.perform(get("/api/costs/projects/7").param("year","2026"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.project.free_cost_amt").value(320))
            .andExpect(jsonPath("$.data.project.cumulative_cost_amt").value(320));
    }
    @Test void internalSuppressesBusinessRevenueButKeepsResearchCost() throws Exception {
        project(source("IN",null,"20"),320,0,"N");
        mvc.perform(get("/api/costs/projects/7").param("year","2026"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.project.cur_cntrct_amt").isEmpty())
            .andExpect(jsonPath("$.data.project.profit_amt").isEmpty()).andExpect(jsonPath("$.data.project.margin_rt").isEmpty())
            .andExpect(jsonPath("$.data.project.budget_use_rt").isEmpty());
    }
    @ParameterizedTest @CsvSource({"375,15,false","376,15,true"})
    void lowMarginGapIsStrictlyGreaterThanFive(long cost,int ignored,boolean low) throws Exception {
        project(source("MO",400L,"30"),cost,500,"N");
        mvc.perform(get("/api/costs/projects/7").param("year","2026"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.project.low_margin").value(low));
    }
    @ParameterizedTest @ValueSource(strings={"TK","MO","DP","IN"})
    void simulationIsAnonymousVirtualAndNeverWrites(String type) throws Exception {
        project(source(type,400L,"20"),320,200,"N");
        mvc.perform(post("/api/costs/projects/7/simulate").contentType("application/json")
            .content("{\"headcount_delta\":1,\"monthly_cost_amt\":20,\"months\":2,\"monthly_price_amt\":30}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.simulated").value(true))
            .andExpect(jsonPath("$.data.baseline_cost_amt").value(320))
            .andExpect(jsonPath("$.data.additional_cost_amt").value(40)).andExpect(jsonPath("$.data.simulated_cost_amt").value(360));
        verify(mapper,never()).download(any());
    }
    @Test void releaseWithinCurrentHeadcountAllowsNegativeAdditionalAmounts() throws Exception {
        mvc.perform(post("/api/costs/projects/7/simulate").contentType("application/json")
            .content("{\"headcount_delta\":-1,\"monthly_cost_amt\":20,\"months\":2}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.additional_cost_amt").value(-40));
    }
    @ParameterizedTest @ValueSource(strings={
        "{\"headcount_delta\":-2,\"monthly_cost_amt\":1,\"months\":1}",
        "{\"headcount_delta\":1,\"monthly_cost_amt\":-1,\"months\":1}",
        "{\"headcount_delta\":1,\"monthly_cost_amt\":1.5,\"months\":1}",
        "{\"headcount_delta\":1,\"monthly_cost_amt\":9223372036854775808,\"months\":1}",
        "{\"headcount_delta\":2,\"monthly_cost_amt\":9223372036854775807,\"months\":2}",
        "{\"headcount_delta\":1,\"monthly_cost_amt\":1,\"months\":0}",
        "{\"headcount_delta\":1,\"monthly_cost_amt\":1,\"months\":1,\"extra\":1}",
        "{\"headcount_delta\":1.1,\"monthly_cost_amt\":1,\"months\":1}"})
    void invalidSimulationInputHasNoWrite(String input) throws Exception {
        mvc.perform(post("/api/costs/projects/7/simulate").contentType("application/json").content(input))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verify(mapper,never()).download(any());
    }
    @Test void monthlySimulationRequiresPrice() throws Exception {
        project(source("MO",400L,"20"),320,200,"N");
        mvc.perform(post("/api/costs/projects/7/simulate").contentType("application/json")
            .content("{\"headcount_delta\":1,\"monthly_cost_amt\":1,\"months\":1}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("monthly_price_amt"));
    }
    @ParameterizedTest @ValueSource(strings={"/7","/7/causes"})
    void missingYearIsRejected(String suffix) throws Exception {
        mvc.perform(get("/api/costs/projects"+suffix)).andExpect(status().isBadRequest());
    }
    @ParameterizedTest @ValueSource(strings={"/0","/-1","/abc","/9223372036854775808"})
    void invalidIdentifierIsValidationError(String suffix) throws Exception {
        mvc.perform(get("/api/costs/projects"+suffix).param("year","2026"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
    @ParameterizedTest @ValueSource(strings={"/99","/99/causes"})
    void absentProjectReturns404(String suffix) throws Exception {
        mvc.perform(get("/api/costs/projects"+suffix).param("year","2026")).andExpect(status().isNotFound());
    }
    @Test void absentSimulationProjectReturns404() throws Exception {
        mvc.perform(post("/api/costs/projects/99/simulate").contentType("application/json")
            .content("{\"headcount_delta\":0,\"monthly_cost_amt\":0,\"months\":1}"))
            .andExpect(status().isNotFound());
    }
    @ParameterizedTest @CsvSource({"year,2027","year,0","partId,-1","status,UNKNOWN","type,UNKNOWN","sort,'prj_nm;drop,asc'","page,-1"})
    void listFiltersAreValidated(String field,String value) throws Exception {
        var request=get("/api/costs/projects").param("year",field.equals("year")?value:"2026").accept("application/json");
        if (!field.equals("year")) request.param(field,value);
        mvc.perform(request)
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
    @Test void closedSnapshotErrorPropagatesWithoutFallback() throws Exception {
        when(allocations.year(2026,7L)).thenThrow(ApiException.conflict("PART_STANDARD_MISSING","마감 배분자료 없음","month"));
        mvc.perform(get("/api/costs/projects/7").param("year","2026"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PART_STANDARD_MISSING"));
    }
    @Test void summaryAndTotalUseFullFilteredSetBeforePagination() throws Exception {
        mvc.perform(get("/api/costs/projects").param("year","2026").param("page","1").param("q","원가").accept("application/json"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty())
            .andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.summary.confirmed_supply_amt").value(200));
        verify(mapper).sources(argThat(q->q.q().equals("원가")));
    }
    @Test void xlsxNegotiatesSameListExportsAllRowsAndAuditsAnonymousDownload() throws Exception {
        byte[] bytes=mvc.perform(get("/api/costs/projects").param("year","2026").param("page","1")
            .accept("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (var book=new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(book.getSheetAt(0).getLastRowNum()).isEqualTo(4);
            assertThat(book.getSheetAt(0).getRow(4).getCell(0).getNumericCellValue()).isEqualTo(7);
        }
        verify(mapper).download(argThat(d->d.actor()==null && d.description().contains("1")));
    }
}
