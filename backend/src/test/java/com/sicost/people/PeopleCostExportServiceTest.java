package com.sicost.people;

import com.sicost.common.api.ApiException;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.excel.ExcelExportService.ExportColumn;
import com.sicost.common.security.*;
import java.io.ByteArrayInputStream;
import java.time.*;
import java.util.*;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import static com.sicost.people.PeopleCostModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PeopleCostExportServiceTest {
    final PeopleCostService people=mock(PeopleCostService.class);
    final PeopleCostMapper mapper=mock(PeopleCostMapper.class);
    final Clock clock=Clock.fixed(Instant.parse("2026-02-15T00:00:00Z"),ZoneOffset.UTC);
    final ExcelExportService excel=spy(new ExcelExportService());
    final PeopleCostExportService service=new PeopleCostExportService(people,excel,mapper,new CurrentUser(),new PermissionService(),clock);
    // Test-only mapping exercises the extension point, not a claimed approved B~W mapping.
    List<ExportColumn<Row>> columns() {
        var result=new ArrayList<ExportColumn<Row>>();
        for(var field:Arrays.stream(Row.class.getRecordComponents()).limit(22).toList()) {
            result.add(new ExportColumn<>(field.getName(),field.getName(),true,false,row->{
                try { return field.getAccessor().invoke(row); }
                catch(ReflectiveOperationException ex) { throw new IllegalStateException(ex); }
            }));
        }
        return result;
    }
    @BeforeEach void setup() {
        var row=mock(Row.class); when(row.name()).thenReturn("=SUM(A1:A2)");
        when(people.prepare(any())).thenReturn(new Prepared(List.of(row,row),null,List.of(),List.of(),"2026-02"));
    }
    @Test void createsRealWorkbookFromUnpagedPreparedRowsThenAuditsAndTreatsFormulaAsText() throws Exception {
        var result=service.export(Map.of("year","2026","page","9","size","1","lossOnly","true"),columns());
        try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(Base64.getDecoder().decode(result.content_base64())))) {
            var sheet=workbook.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isEqualTo(5);
            assertThat(sheet.getRow(4).getCell(1).getCellType()).isEqualTo(CellType.STRING);
            assertThat(sheet.getRow(4).getCell(1).getStringCellValue()).isEqualTo("=SUM(A1:A2)");
        }
        var order=inOrder(excel,mapper);
        order.verify(excel).create(anyString(),anyString(),any(),anyList(),anyList());
        order.verify(mapper).access(argThat(a->a.detail().contains("\"rowCount\":2")&&a.detail().contains("lossOnly")&&a.actor()==null));
        verify(people).prepare(argThat(q->q.lossOnly()&&q.page()==9&&q.size()==1));
    }
    @Test void missingOrDuplicateMappingDoesNotCalculateOrAudit() {
        assertThatThrownBy(()->service.export(Map.of("year","2026"),List.of())).isInstanceOf(ApiException.class);
        var duplicate=Collections.nCopies(22,columns().get(0));
        assertThatThrownBy(()->service.export(Map.of("year","2026"),duplicate)).isInstanceOf(ApiException.class);
        verifyNoInteractions(people,mapper);
    }
    @Test void workbookFailureNeverWritesDownloadAudit() {
        doThrow(new IllegalStateException("workbook failed")).when(excel).create(anyString(),anyString(),any(),anyList(),anyList());
        assertThatThrownBy(()->service.export(Map.of("year","2026"),columns())).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(mapper);
    }
    @Test void auditFailurePropagatesRatherThanReportingDownloadSuccess() {
        doThrow(new IllegalStateException("audit failed")).when(mapper).access(any());
        assertThatThrownBy(()->service.export(Map.of("year","2026"),columns())).isInstanceOf(IllegalStateException.class);
    }
}
