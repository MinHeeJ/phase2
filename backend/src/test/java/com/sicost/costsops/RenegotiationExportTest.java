package com.sicost.costsops;

import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.excel.ExcelExportService.ExportColumn;
import com.sicost.common.security.*;
import java.io.ByteArrayInputStream;
import java.time.*;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import static com.sicost.costsops.RenegotiationModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RenegotiationExportTest {
    final RenegotiationService service=mock(RenegotiationService.class);
    final RenegotiationMapper mapper=mock(RenegotiationMapper.class);
    final Clock clock=Clock.fixed(Instant.parse("2026-10-23T00:00:00Z"),ZoneId.of("Asia/Seoul"));
    final Query query=RenegotiationQuery.parse(Map.of("page","9","size","1"));
    List<ExportColumn<Row>> columns() {
        return List.of(new ExportColumn<>("dev_nm","사람",false,false,Row::dev_nm),
            new ExportColumn<>("allocated_cost_amt","월 배분원가",true,true,Row::allocated_cost_amt));
    }
    Data data() {
        var row=new RenegotiationCalculatorTest().row(1,"1.00",100L,90);
        return new Data("2026-10",false,List.of(row,row),2,0,2,RenegotiationCalculator.summary(List.of(row,row)),List.of());
    }
    @Test void realWorkbookUsesUnpagedCalculationThenAuditsAfterCreation() throws Exception {
        when(service.unpaged(query)).thenReturn(data());
        var exporter=new RenegotiationExportService(service,mapper,new ExcelExportService(),new CurrentUser(),new PermissionService(),clock);
        var result=exporter.create(query,columns());
        try (var book=new XSSFWorkbook(new ByteArrayInputStream(Base64.getDecoder().decode(result.content_base64())))) {
            assertThat(book.getSheetAt(0).getLastRowNum()).isEqualTo(5);
            assertThat(book.getSheetAt(0).getRow(4).getCell(1).getNumericCellValue()).isEqualTo(90);
        }
        verify(service).unpaged(query);
        verify(mapper).access(argThat(a -> a.actor()==null && a.detail().contains("\"rowCount\":2")
            && a.detail().contains("\"sensitive\":true")));
    }
    @Test void workbookFailureDoesNotWriteAudit() {
        when(service.unpaged(query)).thenReturn(data());
        var excel=mock(ExcelExportService.class);
        when(excel.create(anyString(),anyString(),any(),anyList(),anyList())).thenThrow(new IllegalStateException("workbook failure"));
        var exporter=new RenegotiationExportService(service,mapper,excel,new CurrentUser(),new PermissionService(),clock);
        assertThatThrownBy(() -> exporter.create(query,columns())).hasMessage("workbook failure");
        verifyNoInteractions(mapper);
    }
    @Test void absentApprovedColumnsCannotProducePlaceholderDownload() {
        var exporter=new RenegotiationExportService(service,mapper,new ExcelExportService(),new CurrentUser(),new PermissionService(),clock);
        assertThatThrownBy(() -> exporter.create(query,List.of())).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(service,mapper);
    }
}
