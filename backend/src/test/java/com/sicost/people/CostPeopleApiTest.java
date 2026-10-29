package com.sicost.people;

import com.sicost.common.api.*;
import com.sicost.common.costallocation.*;
import com.sicost.common.security.*;
import com.sicost.common.excel.ExcelExportService;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.web.servlet.MockMvc;
import static com.sicost.common.costallocation.CostAllocationModels.*;
import static com.sicost.people.CostPeopleModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = CostPeopleController.class)
@Import({RequestIdFilter.class, ApiExceptionHandler.class, CostPeopleAdvice.class, CostPeopleService.class,
    CostPeopleExportService.class, ExcelExportService.class, CurrentUser.class,
    CostAllocationService.class, CostAllocationEngine.class, CostPeopleApiTest.Time.class})
class CostPeopleApiTest {
    @TestConfiguration static class Time {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-10-14T00:00:00Z"), ZoneId.of("Asia/Seoul")); }
    }
    @Autowired MockMvc mvc;
    @Autowired CostPeopleService service;
    @Autowired Clock clock;
    @MockBean CostPeopleMapper mapper;
    @MockBean CostAllocationMapper sources;
    @MockBean PermissionService permissions;
    static CostPeopleModels.Person person(long id, boolean leave) {
        return new CostPeopleModels.Person(id, "K-" + id, "개발자" + id, "자사", null, 1L,
            "MEMBER", "FT", "DIRECT", "BE", "SENIOR", leave);
    }
    static Payroll payroll(long id) { return new Payroll(id, 100L, 0L, 0L, 0L, 0L, 0L, 100L, 100L, 0, 0); }
    static Assignment assignment(long id, long project, String mm, String type, Long price) {
        return new Assignment(id, 7, project, new BigDecimal(mm), "ACTIVE", type, "RUN", "NEW", price, 0L);
    }
    static Project project(long id, long project, String type) {
        return new Project(id, 7, project, "프로젝트" + project, type,
            LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
    }
    @BeforeEach void setup() {
        when(mapper.people("2026-10")).thenReturn(List.of(person(7, false)));
        when(mapper.payroll("2026-10")).thenReturn(List.of(payroll(7)));
        when(mapper.projects("2026-10")).thenReturn(List.of(project(1, 101, "MO")));
        when(mapper.person(7)).thenReturn(7L);
        when(sources.people("2026-10")).thenReturn(List.of(new CostAllocationModels.Person(7, "FT", "DIRECT", 100L)));
        when(sources.assignments("2026-10")).thenReturn(List.of(assignment(1, 101, "0.40", "MO", 0L)));
    }
    @Test void anonymousTotalIncludesIdleAndSensitiveAmountsWithMetadata() throws Exception {
        mvc.perform(get("/api/costs/people").param("year", "2026"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.meta").isMap()).andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.page").value(0)).andExpect(jsonPath("$.data.size").value(50))
            .andExpect(jsonPath("$.data.month").value("2026-10"))
            .andExpect(jsonPath("$.data.referenceMonth").value("2026-10"))
            .andExpect(jsonPath("$.data.unit").value("KRW"))
            .andExpect(jsonPath("$.data.items[0].total_cost_amt").value(100))
            .andExpect(jsonPath("$.data.items[0].profit_amt").value(-100))
            .andExpect(jsonPath("$.data.items[0].margin_rt").isEmpty());
        verify(permissions).can("UI-03.read");
        verify(mapper, never()).download(any());
    }
    @Test void breakdownRetainsProjectAndIdleAndMonthlyEvidence() throws Exception {
        mvc.perform(get("/api/costs/people/7/breakdown").param("year", "2026"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.person.dev_id").value(7))
            .andExpect(jsonPath("$.data.items[0].lbr_cst_amt").value(40))
            .andExpect(jsonPath("$.data.items[1].aloc_ty_cd").value("IDLE"))
            .andExpect(jsonPath("$.data.items[1].lbr_cst_amt").value(60))
            .andExpect(jsonPath("$.data.monthly_rows[0].payroll.slry_amt").value(100));
    }
    @Test void excessMmUsesCommonLargestRemainderAndNoIdle() throws Exception {
        when(sources.assignments("2026-10")).thenReturn(List.of(assignment(1, 101, "1.00", "MO", 0L),
            assignment(2, 102, "0.50", "MO", 0L)));
        mvc.perform(get("/api/costs/people/7/breakdown").param("year", "2026"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(2))
            .andExpect(jsonPath("$.data.items[0].lbr_cst_amt").value(67))
            .andExpect(jsonPath("$.data.items[1].lbr_cst_amt").value(33))
            .andExpect(jsonPath("$.data.person.overlap").value(true))
            .andExpect(jsonPath("$.data.person.total_cost_amt").value(100));
    }
    @Test void monthlyAverageUsesCalendarNotUploadedMonths() throws Exception {
        mvc.perform(get("/api/costs/people").param("year", "2026").param("view", "AVERAGE"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].total_cost_amt").value(10))
            .andExpect(jsonPath("$.data.items[0].profit_amt").value(-10))
            .andExpect(jsonPath("$.data.items[0].slry_amt").value(10));
    }
    @Test void turnkeyExclusionRemovesRevenueNotCost() throws Exception {
        when(mapper.projects("2026-10")).thenReturn(List.of(project(1, 101, "TK")));
        when(sources.assignments("2026-10")).thenReturn(List.of(new Assignment(1, 7, 101,
            new BigDecimal("1.00"), "ACTIVE", "TK", "RUN", "NEW", null, 200L)));
        mvc.perform(get("/api/costs/people").param("year", "2026").param("excludeTurnkeyPrice", "true"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].personal_revenue_amt").value(0))
            .andExpect(jsonPath("$.data.items[0].profit_amt").value(-100))
            .andExpect(jsonPath("$.data.summary.priced_people_count").value(0));
    }
    @Test void leaveIncludedInPeopleButExcludedFromCostProfitAverageZeroPriceIncluded() throws Exception {
        when(mapper.people("2026-10")).thenReturn(List.of(person(7, false), person(8, true)));
        when(mapper.payroll("2026-10")).thenReturn(List.of(payroll(7), payroll(8)));
        when(sources.people("2026-10")).thenReturn(List.of(new CostAllocationModels.Person(7,"FT","DIRECT",100L),
            new CostAllocationModels.Person(8,"FT","DIRECT",100L)));
        mvc.perform(get("/api/costs/people").param("year", "2026"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.summary.people_count").value(2))
            .andExpect(jsonPath("$.data.summary.average_people_count").value(1))
            .andExpect(jsonPath("$.data.summary.average_cost_amt").value(100))
            .andExpect(jsonPath("$.data.summary.average_price_amt").value(0));
    }
    @Test void summaryAndTotalFollowFiltersNotPagination() throws Exception {
        mvc.perform(get("/api/costs/people").param("year","2026").param("page","1").param("size","20")
                .param("lossOnly","true").param("partId","1").param("job","BE").param("contractType","MO"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty())
            .andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.summary.people_count").value(1));
    }
    @ParameterizedTest @CsvSource({"empType,OS", "partId,2", "vendorId,2", "job,FE", "contractType,TK", "overlapOnly,true"})
    void eachOptionalFilterChangesBothCountAndSummary(String field, String value) throws Exception {
        mvc.perform(get("/api/costs/people").param("year","2026").param(field,value))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0))
            .andExpect(jsonPath("$.data.summary.people_count").value(0));
    }
    @Test void missingSalaryRemainsUnavailableAndWarned() throws Exception {
        when(sources.people("2026-10")).thenReturn(List.of(new CostAllocationModels.Person(7,"FT","DIRECT",null)));
        when(mapper.payroll("2026-10")).thenReturn(List.of());
        mvc.perform(get("/api/costs/people").param("year","2026"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].total_cost_amt").isEmpty())
            .andExpect(jsonPath("$.data.items[0].profit_amt").isEmpty())
            .andExpect(jsonPath("$.data.warnings").isNotEmpty());
    }
    @Test void closedSnapshotsNeverReadCurrentPayrollAndKeepFrozenCost() throws Exception {
        when(sources.monthStatus("2026-10")).thenReturn("CLOSED");
        when(sources.snapshots(new CostAllocationModels.Scope("2026-10",null))).thenReturn(List.of(new Allocation("2026-10",7,101L,1L,
            "PROJECT",new BigDecimal("1.00"),new BigDecimal("1.00"),BigDecimal.ONE,777L,9,1000L,1000L,"N","N","N")));
        mvc.perform(get("/api/costs/people").param("year","2026"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CLOSED"))
            .andExpect(jsonPath("$.data.items[0].total_cost_amt").value(786));
        verify(mapper, never()).payroll("2026-10");
        verify(sources, never()).assignments("2026-10");
    }
    @ParameterizedTest @ValueSource(strings={"/api/costs/people","/api/costs/people/7/breakdown"})
    void missingClosedSnapshotIs409OnBothOperations(String path) throws Exception {
        when(sources.monthStatus("2026-10")).thenReturn("CLOSED");
        when(sources.missingSnapshots(any())).thenReturn(1L);
        mvc.perform(get(path).param("year","2026"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH"))
            .andExpect(jsonPath("$.message").value("마감 배분자료 없음"));
    }
    @ParameterizedTest @CsvSource({"year,abc", "year,0", "year,2027", "view,BAD", "empType,BAD", "partId,0",
        "vendorId,abc", "contractType,BAD", "job,../", "lossOnly,1", "overlapOnly,yes", "excludeTurnkeyPrice,on",
        "page,-1", "size,30", "sort,profit_amt;DROP,asc"})
    void malformedParameterHasFeatureErrorAndSingularField(String field, String value) throws Exception {
        var request = get("/api/costs/people").param(field, value);
        if (!"year".equals(field)) request.param("year", "2026");
        mvc.perform(request)
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.field").value(field)).andExpect(jsonPath("$.requestId").isNotEmpty());
        verifyNoInteractions(mapper, sources);
    }
    @Test void missingYearIsValidation() throws Exception {
        mvc.perform(get("/api/costs/people")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.field").value("year")).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
    @Test void unknownDeveloperIs404() throws Exception {
        mvc.perform(get("/api/costs/people/999/breakdown").param("year","2026"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
    @ParameterizedTest @ValueSource(strings={"abc","0","-1","01","9223372036854775808"})
    void malformedDeveloperIdIsValidation(String id) throws Exception {
        mvc.perform(get("/api/costs/people/"+id+"/breakdown").param("year","2026"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("devId"));
    }
    @Test void exportIsRealFullFilteredWorkbookWithAll22ColumnsAndNullActorAudit() throws Exception {
        byte[] bytes=mvc.perform(get("/api/costs/people").param("year","2026").param("page","1")
                .param("lossOnly","true").accept(CostPeopleController.XLSX))
            .andExpect(status().isOk()).andExpect(content().contentType(CostPeopleController.XLSX))
            .andExpect(header().string("Content-Disposition",org.hamcrest.Matchers.containsString(".xlsx")))
            .andReturn().getResponse().getContentAsByteArray();
        try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet=workbook.getSheetAt(0);
            assertThat(sheet.getRow(3).getLastCellNum()).isEqualTo((short)23);
            for(int i=0;i<22;i++) assertThat(sheet.getRow(3).getCell(i+1).getStringCellValue()).isEqualTo(CostPeopleExportService.TITLES.get(i));
            assertThat(sheet.getRow(4).getCell(20).getNumericCellValue()).isEqualTo(100);
            assertThat(sheet.getRow(4).getCell(21).getNumericCellValue()).isEqualTo(-100);
        }
        verify(mapper).download(argThat(a -> a.actor()==null && a.description().contains("columns")));
        verify(permissions).can("UI-03.download");
    }
    @Test void utilizationAveragesMonthlyRatiosNotThePooledHeadcounts() throws Exception {
        for (int i = 1; i <= 10; i++) {
            String month = YearMonth.of(2026, i).toString();
            when(mapper.people(month)).thenReturn(i == 1 ? List.of(person(7, false), person(8, true)) : List.of(person(7, false)));
            when(mapper.payroll(month)).thenReturn(List.of(payroll(7), payroll(8)));
            when(mapper.projects(month)).thenReturn(List.of(project(1, 101, "MO")));
            when(sources.people(month)).thenReturn(i == 1
                ? List.of(new CostAllocationModels.Person(7, "FT", "DIRECT", 100L), new CostAllocationModels.Person(8, "FT", "DIRECT", 100L))
                : List.of(new CostAllocationModels.Person(7, "FT", "DIRECT", 100L)));
            when(sources.assignments(month)).thenReturn(List.of(assignment(1, 101, "1.00", "MO", 0L)));
        }
        mvc.perform(get("/api/costs/people").param("year", "2026"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.summary.utilization_rt").value(95))
            .andExpect(jsonPath("$.data.summary.paid_rt").value(95))
            .andExpect(jsonPath("$.data.summary.monthly_active_count").value(1));
    }
    @Test void zeroHeadcountDenominatorIsUnavailableNotZeroOrOneHundred() throws Exception {
        when(mapper.people("2026-10")).thenReturn(List.of());
        mvc.perform(get("/api/costs/people").param("year", "2026"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.summary.utilization_rt").isEmpty())
            .andExpect(jsonPath("$.data.summary.paid_rt").isEmpty())
            .andExpect(jsonPath("$.data.warnings").isNotEmpty());
    }
    @Test void selfAffiliationFilterMatchesNullVendorWithoutChangingCostDenominators() throws Exception {
        mvc.perform(get("/api/costs/people").param("year", "2026").param("vendorId", "SELF"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items[0].total_cost_amt").value(100));
    }
    @Test void descendingAmountSortKeepsNullLastAndUsesNumericIdTieBreak() throws Exception {
        when(mapper.people("2026-10")).thenReturn(List.of(person(9,false),person(8,false),person(7,false)));
        when(mapper.payroll("2026-10")).thenReturn(List.of(payroll(7),payroll(8)));
        when(sources.people("2026-10")).thenReturn(List.of(new CostAllocationModels.Person(7,"FT","DIRECT",100L),
            new CostAllocationModels.Person(8,"FT","DIRECT",100L),new CostAllocationModels.Person(9,"FT","DIRECT",null)));
        mvc.perform(get("/api/costs/people").param("year","2026").param("sort","total_cost_amt,desc"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].dev_id").value(7))
            .andExpect(jsonPath("$.data.items[1].dev_id").value(8))
            .andExpect(jsonPath("$.data.items[2].dev_id").value(9));
    }
    @Test void exportAuditFailureDoesNotReturnBinarySuccess() throws Exception {
        doThrow(new IllegalStateException("internal")).when(mapper).download(any());
        mvc.perform(get("/api/costs/people").param("year","2026").accept(CostPeopleController.XLSX))
            .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("SERVER_ERROR"));
    }
}
