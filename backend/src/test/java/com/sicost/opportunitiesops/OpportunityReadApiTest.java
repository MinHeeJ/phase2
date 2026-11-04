package com.sicost.opportunitiesops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.finance.MonthlyProfitReader;
import com.sicost.common.security.CurrentUser;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.web.servlet.MockMvc;
import static com.sicost.opportunitiesops.OpportunityForecastModels.*;
import static com.sicost.opportunitiesops.OpportunityLifecycleModels.ExportRow;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers=OpportunityLifecycleController.class)
@Import({RequestIdFilter.class,ApiExceptionHandler.class,OpportunityExportService.class,
    WorkforceForecastService.class,ExcelExportService.class,CurrentUser.class,OpportunityReadApiTest.Time.class})
class OpportunityReadApiTest {
    @TestConfiguration static class Time {
        @Bean Clock clock(){return Clock.fixed(Instant.parse("2026-10-31T15:01:00Z"),ZoneId.of("Asia/Seoul"));}
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean OpportunityLifecycleService lifecycle;
    @MockBean OpportunityWinService wins;
    @MockBean OpportunityLifecycleMapper exports;
    @MockBean OpportunityForecastMapper mapper;
    @MockBean MonthlyProfitReader profit;

    @BeforeEach void setup(){when(mapper.code(any())).thenReturn(true);}

    @Test void forecastReturnsSourceRevenueAndSeparateTemporaryMm()throws Exception {
        when(mapper.opportunities(any())).thenReturn(List.of(new Opportunity(7,1001,"2026-12",2,new BigDecimal("55"))));
        when(mapper.projects()).thenReturn(List.of(new Project(8,"TK",LocalDate.of(2026,12,1),LocalDate.of(2027,1,31),1001L)));
        when(mapper.needs()).thenReturn(List.of(new Demand(7,"BE","SR","2026-12",2,2,BigDecimal.ONE,new BigDecimal("55"))));
        when(mapper.temporary()).thenReturn(List.of(new Temporary("BE","SR","2026-12","2027-01",new BigDecimal("0.50"))));
        mvc.perform(get("/api/opportunities/forecast").param("from","2026-12").param("months","2"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.items[0].project_revenue").value(500))
            .andExpect(jsonPath("$.data.items[0].opportunity_revenue").value(275))
            .andExpect(jsonPath("$.data.items[1].project_revenue").value(501))
            .andExpect(jsonPath("$.data.total").value(2))
            .andExpect(jsonPath("$.data.kpi.weighted_total").value(1551))
            .andExpect(jsonPath("$.data.kpi.all_won_total").value(2002))
            .andExpect(jsonPath("$.data.workforce.items[0].demand_mm").value(1.1))
            .andExpect(jsonPath("$.data.workforce.items[0].confirmed_mm").value(0))
            .andExpect(jsonPath("$.data.workforce.items[0].temporary_mm").value(0.5));
        verifyNoInteractions(lifecycle,wins,exports);
    }

    @Test void filteredWorkforceTotalAndKpiAreIndependentOfPage()throws Exception {
        when(mapper.needs()).thenReturn(List.of(new Demand(7,"BE","SR","2026-12",2,2,BigDecimal.ONE,new BigDecimal("55")),
            new Demand(8,"FE","JR","2026-12",2,9,BigDecimal.ONE,new BigDecimal("100"))));
        mvc.perform(get("/api/opportunities/forecast").param("from","2026-12").param("months","2")
                .param("job","BE").param("grade","SR").param("page","1").param("size","20"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty())
            .andExpect(jsonPath("$.data.total").value(2)).andExpect(jsonPath("$.data.workforce.total").value(2))
            .andExpect(jsonPath("$.data.workforce.items").isEmpty())
            .andExpect(jsonPath("$.data.kpi.average_demand_mm").value(1.1));
        verify(mapper).opportunities(argThat(q->"BE".equals(q.job())&&"SR".equals(q.grade())));
    }

    @ParameterizedTest @CsvSource({"from,0000-01","from,2026-13","months,0","months,7","months,abc",
        "year,10000","page,-1","size,21","sort,'month,down'","job,be","grade,SR!","unknown,x"})
    void invalidForecastDoesNotQuerySources(String field,String value)throws Exception {
        mvc.perform(get("/api/opportunities/forecast").param(field,value)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.field").value(field)).andExpect(jsonPath("$.requestId").isNotEmpty());
        verifyNoInteractions(mapper,profit,exports);
    }

    @Test void unknownJobIsRejectedBeforeSourceReads()throws Exception {
        when(mapper.code(any())).thenReturn(false);
        mvc.perform(get("/api/opportunities/forecast").param("job","UNKNOWN"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("job"));
        verify(mapper,never()).developers(any());
        verify(mapper,never()).opportunities(any());
    }

    @Test void pastYearHasNoInventedMonths()throws Exception {
        mvc.perform(get("/api/opportunities/forecast").param("year","2025"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty())
            .andExpect(jsonPath("$.data.workforce.months").isEmpty())
            .andExpect(jsonPath("$.data.workforce.message").value("예측할 미래월이 없습니다."));
    }

    @Test void exportProducesRealXlsxAndKeepsFormulaLikeNamesAsText()throws Exception {
        when(exports.exportRows(any())).thenReturn(List.of(new ExportRow(7,"O-7","=HYPERLINK(\"https://invalid.example\")",
            "고객","파트","PROP",new BigDecimal("55"),1001,"MO","2026-12",2,"+외부 수식 아님",null,
            "OPEN","PENDING",null,null,2,LocalDateTime.of(2026,11,1,0,1))));
        var result=mvc.perform(get("/api/opportunities/export").param("q","고객").param("from","2026-12"))
            .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("application/json"))
            .andExpect(jsonPath("$.data.file_name").value("영업파이프라인_20261101.xlsx"))
            .andExpect(jsonPath("$.data.media_type").value(DownloadArtifact.XLSX)).andReturn();
        var data=json.readTree(result.getResponse().getContentAsString()).get("data");
        try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(Base64.getDecoder().decode(data.get("content_base64").asText())))){
            var cell=workbook.getSheetAt(0).getRow(4).getCell(1);
            assertThat(cell.getCellType()).isEqualTo(CellType.STRING);
            assertThat(cell.getStringCellValue()).startsWith("=HYPERLINK");
            assertThat(workbook.getSheetAt(0).getRow(4).getCell(6).getNumericCellValue()).isEqualTo(1001);
        }
        verify(exports).exportRows(argThat(q->"고객".equals(q.q())&&"2026-12".equals(q.from())));
        verify(exports).download(argThat(d->d.actor()==null));
        verifyNoInteractions(lifecycle,wins,mapper,profit);
    }

    @Test void reversedExportPeriodFailsWithoutDownloadAudit()throws Exception {
        mvc.perform(get("/api/opportunities/export").param("from","2027-02").param("to","2026-12"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("to"));
        verifyNoInteractions(exports);
    }

    @Test void overlongExportSearchFailsWithoutReadingRows()throws Exception {
        mvc.perform(get("/api/opportunities/export").param("q","가".repeat(201)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("q"));
        verifyNoInteractions(exports);
    }
}
