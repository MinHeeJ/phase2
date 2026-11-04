package com.sicost.dashboardops;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sicost.common.api.*;
import com.sicost.common.dashboard.*;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.security.*;
import java.io.ByteArrayInputStream;
import java.time.*;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.*;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers=DashboardExportController.class)
@Import({RequestIdFilter.class,ApiExceptionHandler.class,DashboardExportService.class,DashboardWorkbook.class,
    DashboardAuditService.class,ExcelExportService.class,CurrentUser.class,PermissionService.class})
class DashboardExportApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean DashboardReadSource source;
    @MockBean DashboardAuditMapper mapper;
    @MockBean Clock clock;
    @SpyBean ExcelExportService excel;
    ObjectNode response;
    DashboardReadContext captured;
    @BeforeEach void setup() {
        var fixed=Clock.fixed(Instant.parse("2026-10-31T15:00:00Z"),ZoneOffset.UTC);
        when(clock.instant()).thenReturn(fixed.instant());
        when(clock.getZone()).thenReturn(fixed.getZone());
        when(clock.withZone(any())).thenAnswer(i->fixed.withZone(i.getArgument(0)));
        when(mapper.download(any())).thenReturn(1);
        when(source.read(any())).thenAnswer(i->{
            captured=i.getArgument(0);response=DashboardExportFixtures.response(json,captured);return response;
        });
    }
    byte[] download(String month) throws Exception {
        var request=get("/api/dashboard/export");if(month!=null)request.param("month",month);
        return mvc.perform(request).andExpect(status().isOk())
            .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .andExpect(header().string("Content-Disposition",org.hamcrest.Matchers.containsString("attachment;")))
            .andExpect(header().exists("X-Request-Id")).andReturn().getResponse().getContentAsByteArray();
    }
    @Test void anonymousDownloadDefaultsToSeoulMonthAndAuditsOnceAfterWorkbook() throws Exception {
        byte[] bytes=download(null);
        assertThat(captured.month().toString()).isEqualTo("2026-11");
        assertThat(captured.today()).isEqualTo(LocalDate.of(2026,11,1));
        assertThat(bytes[0]).isEqualTo((byte)'P');assertThat(bytes[1]).isEqualTo((byte)'K');
        verify(clock,times(1)).instant();verify(source,times(1)).read(any());
        var order=inOrder(excel,mapper);
        order.verify(excel).createSheets(anyString(),anyString(),any(),anyList());
        order.verify(mapper).download(argThat(d->d.actor()==null && d.name().equals("dashboard_2026-11.xlsx")
            && d.description().contains("basisMonth") && d.description().contains("columns")));
        verifyNoMoreInteractions(mapper);
    }
    @Test void pastMonthDoesNotMoveTodayForecastAndFilenameUsesValidatedMonth() throws Exception {
        mvc.perform(get("/api/dashboard/export").param("month","2025-03"))
            .andExpect(status().isOk()).andExpect(header().string("Content-Disposition",org.hamcrest.Matchers.containsString("dashboard_2025-03.xlsx")));
        assertThat(captured.month().toString()).isEqualTo("2025-03");
        assertThat(response.path("workforceForecast").path("fromMonth").asText()).isEqualTo("2026-12");
    }
    @ParameterizedTest @ValueSource(strings={"","2026-13","2026-00","0000-01","2026-1","2026-11-01"," 2026-10","20260-01","abc"})
    void invalidMonthIs400BeforeQueryOrAudit(String month) throws Exception {
        mvc.perform(get("/api/dashboard/export").param("month",month))
            .andExpect(status().isBadRequest()).andExpect(content().contentType("application/json"))
            .andExpect(jsonPath("$.code").value("INVALID_MONTH")).andExpect(jsonPath("$.field").value("month"));
        verifyNoInteractions(source,mapper);
    }
    @Test void futureMonthIs400BeforeQueryOrAudit() throws Exception {
        mvc.perform(get("/api/dashboard/export").param("month","2026-12"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("FUTURE_MONTH"))
            .andExpect(jsonPath("$.field").value("month"));
        verifyNoInteractions(source,mapper);
    }
    @Test void everyScalarAndEmptyArrayInQueryResponseSurvivesAcrossTenSheets() throws Exception {
        byte[] bytes=download("2026-10");
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(book.getNumberOfSheets()).isEqualTo(10);
            Map<String,Cell> exported=new HashMap<>();
            for(int s=0;s<book.getNumberOfSheets();s++) {
                var sheet=book.getSheetAt(s);
                assertThat(sheet.getSheetName()).isEqualTo(DashboardWorkbook.sheetNames().get(s));
                String basis=sheet.getRow(0).getCell(0).getStringCellValue();
                assertThat(basis).contains("2026-10","2026-11-01","오늘 기준 예측","VAT 제외","VAT 포함");
                for(int r=4;r<=sheet.getLastRowNum();r++) {
                    var row=sheet.getRow(r);exported.put(row.getCell(0).getStringCellValue(),row.getCell(1));
                }
            }
            compare("",response,exported);
            assertThat(exported.get("kpis.receivables.amount").getStringCellValue()).isEqualTo("9223372036854775807");
            assertThat(exported.get("projectAlerts.items[0].projectName").getCellType()).isEqualTo(CellType.STRING);
            assertThat(exported.get("kpis.ytdOperatingProfit.amount").getNumericCellValue()).isZero();
            assertThat(exported.get("kpis.utilization.confirmedMm").getCellType()).isEqualTo(CellType.BLANK);
        }
    }
    void compare(String path,JsonNode node,Map<String,Cell> cells) {
        if(node.isObject()) node.fields().forEachRemaining(e->compare(path.isEmpty()?e.getKey():path+"."+e.getKey(),e.getValue(),cells));
        else if(node.isArray() && !node.isEmpty()) {
            for(int i=0;i<node.size();i++) compare(path+"["+i+"]",node.get(i),cells);
        } else {
            assertThat(cells).containsKey(path);var cell=cells.get(path);
            if(node.isNull()) assertThat(cell.getCellType()).isEqualTo(CellType.BLANK);
            else if(node.isBoolean()) assertThat(cell.getBooleanCellValue()).isEqualTo(node.booleanValue());
            else if(node.isNumber() && node.decimalValue().precision()<=15) assertThat(cell.getNumericCellValue()).isEqualTo(node.doubleValue());
            else assertThat(cell.getStringCellValue()).isEqualTo(node.isArray()?"[]":node.asText());
        }
    }
    @Test void failedAndExcludedAreasKeepNullAndUnavailableReasonWithoutDroppingOtherSheets() throws Exception {
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(download("2026-10")))) {
            var sheet=book.getSheet("3개월 자금");
            boolean reason=false;
            for(int i=4;i<=sheet.getLastRowNum();i++) {
                var row=sheet.getRow(i);
                if(row.getCell(0).getStringCellValue().endsWith("expectedFinalBalance")) {
                    assertThat(row.getCell(1).getCellType()).isEqualTo(CellType.BLANK);
                    assertThat(row.getCell(3).getStringCellValue()).isEqualTo("조회 불가");
                    assertThat(row.getCell(4).getStringCellValue()).contains("기초 잔액 확인 필요");reason=true;
                }
            }
            assertThat(reason).isTrue();assertThat(book.getSheet("KPI")).isNotNull();
        }
    }
    @Test void missingSectionIsExplicitlyUnavailableNotAnEmptySuccessfulSheet() throws Exception {
        doAnswer(i->{var c=i.getArgument(0,DashboardReadContext.class);
            var value=DashboardExportFixtures.response(json,c);value.remove("partSummary");return value;}).when(source).read(any());
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(download("2026-10")))) {
            var row=book.getSheet("영업 파트").getRow(4);
            assertThat(row.getCell(3).getStringCellValue()).isEqualTo("조회 불가");
            assertThat(row.getCell(4).getStringCellValue()).contains("필수 선행영역");
        }
    }
    @Test void queryFailureDoesNotAuditOrExposeInternalDetails() throws Exception {
        doThrow(new IllegalStateException("private database details")).when(source).read(any());
        mvc.perform(get("/api/dashboard/export")).andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("SERVER_ERROR"))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private database"))));
        verifyNoInteractions(mapper);
    }
    @Test void workbookFailureNeverRecordsSuccessfulDownload() throws Exception {
        doThrow(new IllegalStateException("workbook error")).when(excel).createSheets(anyString(),anyString(),any(),anyList());
        mvc.perform(get("/api/dashboard/export")).andExpect(status().isInternalServerError()).andExpect(content().contentType("application/json"));
        verifyNoInteractions(mapper);
    }
    @Test void mismatchedSourceBasisIsRefusedBeforeWorkbookAndAudit() throws Exception {
        doAnswer(i->{var c=i.getArgument(0,DashboardReadContext.class);
            return DashboardExportFixtures.response(json,c).put("basisMonth","2025-01");}).when(source).read(any());
        mvc.perform(get("/api/dashboard/export")).andExpect(status().isInternalServerError());
        verifyNoInteractions(mapper);
    }
    @Test void auditFailureReturnsJsonNotSuccessfulBytesAndDoesNotRetryInsert() throws Exception {
        when(mapper.download(any())).thenThrow(new IllegalStateException("audit error"));
        mvc.perform(get("/api/dashboard/export")).andExpect(status().isInternalServerError()).andExpect(content().contentType("application/json"));
        verify(mapper,times(1)).download(any());
    }
    @Test void closedValuesAreOnlyCopiedAndNeverRecalculated() throws Exception {
        doAnswer(i->{var c=i.getArgument(0,DashboardReadContext.class);
            var value=DashboardExportFixtures.response(json,c).put("closeStatus","CLOSED");
            ((ObjectNode)value.path("kpis").path("revenue")).put("amount",713333);return value;}).when(source).read(any());
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(download("2026-09")))) {
            var sheet=book.getSheet("KPI");boolean found=false;
            for(int i=4;i<=sheet.getLastRowNum();i++) if(sheet.getRow(i).getCell(0).getStringCellValue().equals("kpis.revenue.amount")) {
                assertThat(sheet.getRow(i).getCell(1).getNumericCellValue()).isEqualTo(713333);found=true;
            }
            assertThat(found).isTrue();verify(source,times(1)).read(any());
        }
    }
}
