package com.sicost.businessplan;

import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.security.PermissionService;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import static com.sicost.businessplan.BusinessPlanModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BusinessPlanComparisonTest {
    BusinessPlanMapper mapper;
    BusinessPlanActualsService actuals;
    BusinessPlanComparisonService service;
    @BeforeEach void setup() {
        mapper=mock(BusinessPlanMapper.class);actuals=mock(BusinessPlanActualsService.class);
        service=new BusinessPlanComparisonService(mapper,actuals,new ExcelExportService(),new PermissionService(),
            Clock.fixed(Instant.parse("2026-10-30T00:00:00Z"),ZoneId.of("Asia/Seoul")));
        when(mapper.list(2026)).thenReturn(List.of(new Plan(2026,1,null,100,10,BigDecimal.valueOf(80)),
            new Plan(2026,2,null,900,90,BigDecimal.valueOf(90))));
        when(actuals.read(2026,null)).thenReturn(List.of(new Actual(1,200L,20L,BigDecimal.valueOf(80),List.of()),
            new Actual(2,450L,45L,BigDecimal.valueOf(90),List.of())));
    }
    @Test void cumulativeDividesTotalsNotMonthlyAchievementAverage() {
        var result=service.compare(2026,2,null);
        assertThat(result.comparison().cumulative().revenue().achievement_rate()).isEqualByComparingTo("65.0");
        assertThat(result.comparison().cumulative().revenue().difference()).isEqualByComparingTo("-350");
        assertThat(result.comparison().months().get(0).revenue().achievement_rate()).isEqualByComparingTo("200.0");
    }
    @Test void zeroAndMissingTargetsAreDistinctAndNeverDivideByZero() {
        when(mapper.list(2026)).thenReturn(List.of(new Plan(2026,1,null,0,0,BigDecimal.ZERO)));
        var result=service.compare(2026,2,null);
        assertThat(result.comparison().months().get(0).target_present()).isTrue();
        assertThat(result.comparison().months().get(0).revenue().achievement_rate()).isNull();
        assertThat(result.comparison().months().get(0).revenue().target()).isZero();
        assertThat(result.comparison().months().get(1).target_present()).isFalse();
        assertThat(result.comparison().months().get(1).revenue().target()).isNull();
        assertThat(result.comparison().cumulative().revenue().achievement_rate()).isNull();
    }
    @Test void missingActualIsNotSilentlyReducedToZero() {
        when(actuals.read(2026,null)).thenReturn(List.of(new Actual(1,null,null,null,List.of("MISSING_CLOSED_SNAPSHOT"))));
        var result=service.compare(2026,2,null);
        assertThat(result.actuals().get(0).revenue()).isNull();
        assertThat(result.comparison().cumulative().revenue().actual()).isNull();
        assertThat(result.warnings()).contains("MISSING_CLOSED_SNAPSHOT");
    }
    @Test void partFilterDoesNotAddCompanyOrOtherPartTargets() {
        when(mapper.parts()).thenReturn(List.of(new Part(7,"영업",true)));
        when(mapper.list(2026)).thenReturn(List.of(new Plan(2026,1,7L,300,30,BigDecimal.TEN),new Plan(2026,1,8L,999,99,BigDecimal.TEN)));
        when(actuals.read(2026,7L)).thenReturn(List.of(new Actual(1,150L,null,BigDecimal.TEN,List.of("MISSING_PART_PROFIT_SOURCE"))));
        var result=service.compare(2026,1,7L);
        assertThat(result.items()).hasSize(1);assertThat(result.comparison().cumulative().revenue().achievement_rate()).isEqualByComparingTo("50.0");
        assertThat(result.comparison().cumulative().operating_profit().actual()).isNull();
        assertThat(result.warnings()).contains("MISSING_PART_PROFIT_SOURCE");
    }
    @Test void unknownPartIs404() {
        assertThatThrownBy(()->service.compare(2026,1,7L)).isInstanceOfSatisfying(com.sicost.common.api.ApiException.class,
            e->assertThat(e.status()).isEqualTo(404));
        verifyNoInteractions(actuals);
    }
    @Test void workbookContainsExactlyTheScreenMonthlyAndCumulativeValues()throws Exception {
        var result=service.compare(2026,2,null);var file=result.export_file();
        assertThat(file.content_type()).isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(file.file_name()).endsWith(".xlsx");
        try(var book=new XSSFWorkbook(new java.io.ByteArrayInputStream(Base64.getDecoder().decode(file.content_base64())))) {
            var sheet=book.getSheetAt(0);
            assertThat(sheet.getRow(4).getCell(2).getNumericCellValue()).isEqualTo(100);
            assertThat(sheet.getRow(4).getCell(3).getNumericCellValue()).isEqualTo(200);
            assertThat(sheet.getRow(40).getCell(0).getStringCellValue()).isEqualTo("1~2월 누계");
            assertThat(sheet.getRow(40).getCell(5).getNumericCellValue()).isEqualTo(65.0);
        }
        verify(mapper,never()).upsert(org.mockito.ArgumentMatchers.any());
    }
}
