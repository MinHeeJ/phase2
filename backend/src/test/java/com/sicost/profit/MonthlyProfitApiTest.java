package com.sicost.profit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.security.PermissionService;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.billingsops.*;
import com.sicost.costallocation.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

@WebMvcTest(controllers=MonthlyProfitController.class)
@Import({RequestIdFilter.class,ApiExceptionHandler.class,MonthlyProfitService.class,PermissionService.class,ExcelExportService.class})
class MonthlyProfitApiTest {
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean MonthlyProfitMapper mapper;
    @MockBean BillingRevenueQuery billing;
    @MockBean CostAllocationService costs;
    @MockBean Clock clock;
    @BeforeEach void setup() {
        when(clock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(clock.instant()).thenReturn(Instant.parse("2026-10-15T00:00:00Z"));
        when(clock.withZone(any())).thenReturn(clock);
        when(mapper.closedMonths(anyInt())).thenReturn(List.of());
        when(mapper.taxMonths(anyInt())).thenReturn(List.of());
        when(mapper.facts(anyString())).thenReturn(new MonthlyProfitModels.Facts(1,0));
        when(mapper.annualTax(anyInt())).thenReturn(120L);
        when(costs.calculate(anyString(),isNull())).thenAnswer(i->new CostAllocationModels.Allocation(i.getArgument(0),"2026-10",false,"원",List.of(),List.of(),0));
        when(billing.revenue(anyInt())).thenAnswer(i->List.of(new BillingModels.Revenue(i.getArgument(0)+"-01",1000)));
    }
    @Test void monthlyAnonymousReturnsTwelveMonthsAndFutureNull() throws Exception {
        mvc.perform(get("/api/finance/profit/monthly?year=2026")).andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.months.length()").value(12))
            .andExpect(jsonPath("$.data.months[0].revenue").value(1000))
            .andExpect(jsonPath("$.data.months[10].revenue").isEmpty())
            .andExpect(jsonPath("$.data.months[10].warning").value("FUTURE_ACTUAL"));
        verify(costs,never()).calculate("2026-11",null);
    }
    @Test void structureAnonymousHasSamePeriodAndComparison() throws Exception {
        mvc.perform(get("/api/finance/profit/structure").param("year","2026").param("month","2").param("period","QUARTER"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.revenue").value(1000))
            .andExpect(jsonPath("$.data.from_month").value(1))
            .andExpect(jsonPath("$.data.previous.revenue").isEmpty());
    }
    @ParameterizedTest @ValueSource(strings={"", "?year=0", "?year=x", "?year=10000"})
    void monthlyValidation(String query) throws Exception {
        mvc.perform(get("/api/finance/profit/monthly"+query)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR")).andExpect(jsonPath("$.field").value("year"));
    }
    @ParameterizedTest @ValueSource(strings={"year=0&month=1&period=MONTH", "year=2026&month=13&period=YTD", "year=2026&month=1&period=bad", "year=2026&month=1"})
    void structureValidation(String query) throws Exception {
        mvc.perform(get("/api/finance/profit/structure?"+query)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
    @Test void closedMissingNeverCallsMutableCostEngine() throws Exception {
        when(mapper.closedMonths(2026)).thenReturn(List.of("2026-01"));
        mvc.perform(get("/api/finance/profit/monthly?year=2026")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.months[0].revenue").isEmpty())
            .andExpect(jsonPath("$.data.months[0].warning").value("MISSING_CLOSED_SNAPSHOT"));
        verify(costs,never()).calculate("2026-01",null);
    }
    @Test void unexpectedMonthlyErrorIsSafe() throws Exception {
        when(mapper.closedMonths(2026)).thenThrow(new IllegalStateException("database password"));
        mvc.perform(get("/api/finance/profit/monthly?year=2026")).andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("password"))));
    }
    @Test void unexpectedStructureErrorIsSafe() throws Exception {
        when(mapper.closedMonths(2026)).thenThrow(new IllegalStateException("database password"));
        mvc.perform(get("/api/finance/profit/structure?year=2026&month=1&period=MONTH"))
            .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }
    @Test void workbookIsRealXlsxAndUsesSameCumulativeNumbers() throws Exception {
        var body=mvc.perform(get("/api/finance/profit/monthly?year=2026")).andReturn().getResponse().getContentAsString();
        var file=json.readTree(body).at("/data/months/1/operating_export_file");
        assertThat(file.get("file_name").asText()).endsWith(".xlsx");
        try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(Base64.getDecoder().decode(file.get("content_base64").asText())))) {
            var sheet=book.getSheetAt(0);
            assertThat(sheet.getRow(3).getCell(13).getStringCellValue()).isEqualTo("누계");
            assertThat(sheet.getRow(4).getCell(13).getNumericCellValue()).isEqualTo(1000);
        }
    }
    CostAllocationModels.AllocationRow allocation(String type,long labor,long expense) {
        return new CostAllocationModels.AllocationRow("2026-01",1L,null,"PROJECT".equals(type)?1L:null,
            type,java.math.BigDecimal.ONE,java.math.BigDecimal.ONE,java.math.BigDecimal.ONE,labor,expense,9000,null,"N","N","N");
    }
    @Test void liveCostsIncludeEachExpenseOnceAndNeverTreatAllocationSalesAsRevenue() throws Exception {
        when(costs.calculate("2026-01",null)).thenReturn(new CostAllocationModels.Allocation("2026-01","2026-10",false,"원",List.of(),
            List.of(allocation("PROJECT",100,10),allocation("IDLE",20,2),allocation("INDIRECT",30,3),allocation("RND",40,4)),0));
        when(mapper.entries("2026-01")).thenReturn(List.of(new MonthlyProfitModels.Entry("SGA","임차료",50),new MonthlyProfitModels.Entry("NOI","이자",8),new MonthlyProfitModels.Entry("NOE","비용",9)));
        when(mapper.adjustment("2026-01")).thenReturn(-7L);
        mvc.perform(get("/api/finance/profit/monthly?year=2026")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.months[0].revenue").value(1000))
            .andExpect(jsonPath("$.data.months[0].cost").value(132))
            .andExpect(jsonPath("$.data.months[0].sga").value(127))
            .andExpect(jsonPath("$.data.months[0].operating_profit").value(734))
            .andExpect(jsonPath("$.data.months[0].net_profit").value(723));
    }
    @Test void closedSnapshotWinsOverMutableSources() throws Exception {
        when(mapper.closedMonths(2026)).thenReturn(List.of("2026-01"));
        when(mapper.snapshot(new MonthlyProfitModels.SnapshotKey("2026-01","CLOSE"))).thenReturn(Map.of("revenue",777L,"operating_profit",25L,"net_profit",20L));
        mvc.perform(get("/api/finance/profit/monthly?year=2026")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.months[0].revenue").value(777)).andExpect(jsonPath("$.data.months[0].source").value("CLOSE"));
        verify(costs,never()).calculate("2026-01",null);verify(mapper,never()).entries("2026-01");
    }
    @Test void importTotalsDoNotFabricateComposition() throws Exception {
        when(mapper.snapshot(any())).thenReturn(Map.of("revenue",100L,"cost",20L,"gross_profit",80L,"operating_profit",60L));
        mvc.perform(get("/api/finance/profit/structure?year=2025&month=1&period=MONTH")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.revenue").value(100)).andExpect(jsonPath("$.data.composition_available").value(false))
            .andExpect(jsonPath("$.data.revenue_by_contract").isEmpty());
        verify(costs,never()).calculate(startsWith("2025-"),any());verify(billing,never()).projectRevenue(any());
    }
    @Test void missingPayrollDoesNotPretendZeroCost() throws Exception {
        when(mapper.facts("2026-01")).thenReturn(new MonthlyProfitModels.Facts(0,0));
        mvc.perform(get("/api/finance/profit/monthly?year=2026")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.months[0].cost").isEmpty()).andExpect(jsonPath("$.data.months[0].warning").value("MISSING_PAYROLL"));
    }
    @Test void missingTaxPlanDoesNotPretendZeroNetIncome() throws Exception {
        when(mapper.annualTax(2026)).thenReturn(null);
        mvc.perform(get("/api/finance/profit/monthly?year=2026")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.months[0].corporate_tax").isEmpty()).andExpect(jsonPath("$.data.months[0].net_profit").isEmpty());
    }
    @Test void draftBillingIsExcludedWithWarning() throws Exception {
        when(mapper.facts("2026-01")).thenReturn(new MonthlyProfitModels.Facts(1,1));
        mvc.perform(get("/api/finance/profit/monthly?year=2026")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.months[0].revenue").value(1000)).andExpect(jsonPath("$.data.months[0].warning").value("UNCONFIRMED_BILLING_EXCLUDED"));
    }
    @Test void taxRemainderBelongsOnlyToLastOpenMonth() throws Exception {
        when(clock.instant()).thenReturn(Instant.parse("2026-12-15T00:00:00Z"));
        when(mapper.annualTax(2026)).thenReturn(125L);
        mvc.perform(get("/api/finance/profit/monthly?year=2026")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.months[0].corporate_tax").value(10)).andExpect(jsonPath("$.data.months[11].corporate_tax").value(15));
    }
    @Test void zeroDenominatorIsNullAndNegativePreviousUsesAbsoluteGrowth() throws Exception {
        when(mapper.snapshot(any())).thenReturn(Map.of("revenue",100L,"cost",200L,"operating_profit",-100L));
        mvc.perform(get("/api/finance/profit/structure?year=2026&month=2&period=MONTH")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.operating_margin").isEmpty()).andExpect(jsonPath("$.data.growth_rate.operating_profit").value(100.0));
    }
    @Test void customerTopFiveUsesPkTieOrderAndRemainder() throws Exception {
        var projects=new ArrayList<BillingModels.ProjectRevenue>();
        for(long id=7;id>=1;id--){projects.add(new BillingModels.ProjectRevenue(id,"2026-01",100));
            when(mapper.project(new MonthlyProfitModels.ProjectMonth("2026-01",id))).thenReturn(new MonthlyProfitModels.Project("TK","턴키",id,"고객"+id,1L,"파트"));}
        when(billing.projectRevenue(any())).thenReturn(projects);
        mvc.perform(get("/api/finance/profit/structure?year=2026&month=1&period=MONTH")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.revenue_by_customer.length()").value(6))
            .andExpect(jsonPath("$.data.revenue_by_customer[0].code").value("1"))
            .andExpect(jsonPath("$.data.revenue_by_customer[5].name").value("그 밖"))
            .andExpect(jsonPath("$.data.revenue_by_customer[5].revenue").value(200));
    }
    @Test void mapperXmlRegistersAllReadStatementsWithoutDatabase() throws Exception {
        var configuration=new org.apache.ibatis.session.Configuration();
        String resource="mappers/profit/MonthlyProfitMapper.xml";
        try(var input=new org.springframework.core.io.ClassPathResource(resource).getInputStream()) {
            new org.apache.ibatis.builder.xml.XMLMapperBuilder(input,configuration,resource,configuration.getSqlFragments()).parse();
        }
        for(String statement:List.of("closedMonths","snapshot","details","entries","adjustment","annualTax","taxMonths","facts","project"))
            assertThat(configuration.hasStatement(MonthlyProfitMapper.class.getName()+"."+statement)).isTrue();
    }
}
