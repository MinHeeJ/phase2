package com.sicost.apiops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.billingsops.*;
import com.sicost.businessplan.*;
import com.sicost.cashflow.*;
import com.sicost.common.api.*;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.security.*;
import com.sicost.dashboardops.*;
import com.sicost.opportunities.*;
import com.sicost.opportunitiesops.*;
import com.sicost.parts.*;
import com.sicost.projects.*;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers={DashboardController.class,DashboardExportController.class})
@Import({RequestIdFilter.class,ApiExceptionHandler.class,DashboardQueryService.class,DashboardSections.class,
    DashboardReadAdapter.class,DashboardExportService.class,DashboardWorkbook.class,DashboardAuditService.class,
    ExcelExportService.class,PermissionService.class,CurrentUser.class})
class DashboardIntegratedApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @MockBean com.sicost.profit.MonthlyProfitService profit;
    @MockBean BusinessPlanService plans;
    @MockBean WorkforceForecastService workforce;
    @MockBean BillingReceivableService receivables;
    @MockBean ClosingQueryService closing;
    @MockBean ProjectBoardService board;
    @MockBean CostProjectService costs;
    @MockBean PartSettlementService parts;
    @MockBean OpportunityService opportunities;
    @MockBean CashFlowService cash;
    @MockBean DashboardActionSources actions;
    @MockBean DashboardAuditMapper audit;
    @MockBean Clock clock;

    static BigDecimal n(String value) {return new BigDecimal(value);}
    static final LocalDate TODAY=LocalDate.of(2026,10,29);
    @BeforeEach void setup() {
        when(clock.instant()).thenReturn(Instant.parse("2026-10-29T00:00:00Z"));
        when(profit.calculate(eq(2026),any())).thenReturn(java.util.stream.IntStream.rangeClosed(1,12)
            .mapToObj(m->Map.<String,Object>of("month",YearMonth.of(2026,m).toString(),"closed",false,
                "revenue",1200000L,"cost",800000L,"operating_profit",200000L)).toList());
        when(plans.list(2026)).thenReturn(new BusinessPlanModels.Plans(List.of(),List.of(),List.of(),2026));
        when(closing.readDashboardClosing(anyString())).thenReturn(new ClosingQueryModels.DashboardClosing("OPEN",null,List.of(),false));
        when(receivables.currentReceivables(any())).thenReturn(new BillingModels.CurrentReceivables(700000,2,500000));
        when(workforce.readUtilization(anyString(),any())).thenReturn(new OpportunityForecastModels.UtilizationRead(n("8"),n("10"),2));
        when(workforce.readSupplyDemand(any())).thenAnswer(i->{var q=i.getArgument(0,OpportunityForecastModels.Query.class);
            var point=new OpportunityForecastModels.Month(q.months().get(0),"BE","S",n("2"),n("2.5"),n("0"),n("0"),n("2"),n("0"),n("0"),n("0.5"),null,0,true,null,0,List.of());
            return new OpportunityForecastModels.WorkforcePage(List.of(point),0,Integer.MAX_VALUE,1,List.of(),q.months(),90,null);});
        when(board.readDashboard(TODAY)).thenReturn(new ProjectBoardModels.DashboardBoard(List.of(),0));
        when(costs.readForProjectsAt(anyList(),anyInt(),any())).thenReturn(List.of());
        when(parts.readSummary(anyString())).thenReturn(List.of(new PartSettlementModels.SummaryRow(71L,"본사","HQ",1200000L,null,"MISSING_COST")));
        when(opportunities.readPipeline(TODAY)).thenReturn(new OpportunityModels.PipelineRead(0,0,0,List.of(),0,0));
        when(cash.readForecast(TODAY)).thenReturn(List.of(new CashFlowModels.ForecastMonth("2026-11",990000L,1000000L),
            new CashFlowModels.ForecastMonth("2026-12",1000000L,1000000L),new CashFlowModels.ForecastMonth("2027-01",1100000L,1000000L)));
        when(actions.read(any())).thenReturn(new DashboardActionSources.Counts(Map.of("MM_EXCESS",2,
            "UNCONTRACTED_ASSIGNMENT",0,"WITHDRAWAL",0,"BILLING_GAP",0,"UNCLOSED_PREVIOUS_MONTH",0,
            "LOW_MARGIN",0,"UNCONFIRMED_ADDITIONAL_CONTRACT",0),Map.of("MM_EXCESS","2026-10"),0,0));
        when(audit.download(any())).thenReturn(1);
    }
    @Test void anonymousCompositionReadsAllSourcesAndCountsKindsNotPeople() throws Exception {
        mvc.perform(get("/api/dashboard").param("month","2026-03")).andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true)).andExpect(header().exists("X-Request-Id"))
            .andExpect(jsonPath("$.data.actions.totalKinds").value(4))
            .andExpect(jsonPath("$.data.actions.urgentKinds").value(3))
            .andExpect(jsonPath("$.data.actions.items[3].type").value("MM_EXCESS"))
            .andExpect(jsonPath("$.data.actions.items[3].affectedCount").value(2))
            .andExpect(jsonPath("$.data.cashForecast.months[0].isShortage").value(true))
            .andExpect(jsonPath("$.data.cashForecast.months[1].isShortage").value(false))
            .andExpect(jsonPath("$.data.partSummary.items[0].classification").value("HQ"));
        verify(board).readDashboard(TODAY);verify(parts).readSummary("2026-03");
        verify(cash).readForecast(TODAY);verifyNoInteractions(audit);
    }
    @Test void connectedExporterUsesSameTypedQueryWithoutSecondClockCaptureAndAuditsOnce() throws Exception {
        var response=json.readTree(mvc.perform(get("/api/dashboard")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
        clearInvocations(clock,profit,board,opportunities,cash);
        var bytes=mvc.perform(get("/api/dashboard/export")).andExpect(status().isOk())
            .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .andExpect(header().string("Content-Disposition",org.hamcrest.Matchers.containsString("dashboard_2026-10.xlsx")))
            .andReturn().getResponse().getContentAsByteArray();
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(book.getNumberOfSheets()).isEqualTo(10);
            var sheet=book.getSheet("KPI");boolean found=false;
            for(var row:sheet)if(row.getCell(0)!=null && "kpis.revenue.amount".equals(row.getCell(0).toString())) {
                assertThat(row.getCell(1).getNumericCellValue()).isEqualTo(response.at("/kpis/revenue/amount").asDouble());found=true;
            }
            assertThat(found).isTrue();
        }
        verify(clock,times(1)).instant();verify(profit,times(1)).calculate(2026,YearMonth.of(2026,10));
        verify(board,times(1)).readDashboard(TODAY);verify(cash,times(1)).readForecast(TODAY);
        verify(audit,times(1)).download(argThat(d->d.name().equals("dashboard_2026-10.xlsx")));
    }
    @Test void projectFailureDoesNotHideCashPipelineOrKpisAndNeverLeaksSql() throws Exception {
        when(board.readDashboard(any())).thenThrow(new IllegalStateException("private SQL host/password"));
        mvc.perform(get("/api/dashboard")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.projectAlerts.state").value("ERROR"))
            .andExpect(jsonPath("$.data.pipeline.state").value("READY"))
            .andExpect(jsonPath("$.data.cashForecast.state").value("READY"))
            .andExpect(jsonPath("$.data.kpis.revenue.amount").value(1200000))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private SQL"))));
        verifyNoInteractions(audit);
    }
    @Test void projectAlertCombinesStatusesAndSelectsOneDeterministicRealSourceCause() throws Exception {
        var project=mock(ProjectBoardModels.BoardRow.class);
        when(project.prj_id()).thenReturn(47L);when(project.prj_nm()).thenReturn("실제 원천 프로젝트");
        when(project.light()).thenReturn("위험");when(project.reasons()).thenReturn(List.of("일정 지연"));
        when(project.cntrct_ty_cd()).thenReturn("TK");
        when(board.readDashboard(TODAY)).thenReturn(new ProjectBoardModels.DashboardBoard(List.of(project),0));
        var cost=mock(CostProjectModels.Row.class);
        when(cost.prj_id()).thenReturn(47L);when(cost.low_margin()).thenReturn(true);
        when(cost.budget_warning()).thenReturn("위험");when(cost.budget_use_rt()).thenReturn(n("120"));
        when(cost.estimated_margin_rt()).thenReturn(n("-10"));
        var causes=new CostProjectModels.Causes(List.of(
            new CostProjectModels.Cause("OVER_MM",500,"투입",null,null,"UI-04",47),
            new CostProjectModels.Cause("COST_RATE",500,"원가",null,null,"UI-04",47)),null,null,"OVER_MM");
        when(costs.readForProjectsAt(anyList(),anyInt(),any())).thenReturn(List.of(new CostProjectModels.Detail(
            "2026-10","2026-10",false,"원",List.of(),cost,List.of(),List.of(),causes)));
        mvc.perform(get("/api/dashboard")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.projectAlerts.items.length()").value(1))
            .andExpect(jsonPath("$.data.projectAlerts.items[0].projectId").value(47))
            .andExpect(jsonPath("$.data.projectAlerts.items[0].severity").value("RISK"))
            .andExpect(jsonPath("$.data.projectAlerts.items[0].statuses",org.hamcrest.Matchers.hasItems("저마진","예산 위험")))
            .andExpect(jsonPath("$.data.projectAlerts.items[0].primaryCostCause.sourceId").value(47))
            .andExpect(jsonPath("$.data.projectAlerts.items[0].primaryCostCause.name").value("COST_RATE"));
    }
    @Test void invalidExportMonthDoesNotReadOrAudit() throws Exception {
        mvc.perform(get("/api/dashboard/export").param("month","2026-13")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_MONTH")).andExpect(jsonPath("$.field").value("month"));
        verifyNoInteractions(profit,board,cash,opportunities,audit);
    }
}
