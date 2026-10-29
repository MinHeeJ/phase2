package com.sicost.projects;

import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.security.*;
import java.io.ByteArrayInputStream;
import java.time.*;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.sicost.projects.ProjectProfitModels.*;

class ProjectProfitExportTest {
    final ProjectProfitService projects=mock(ProjectProfitService.class);
    final ProjectProfitMapper mapper=mock(ProjectProfitMapper.class);
    final ExcelExportService excel=spy(new ExcelExportService());
    final ProjectProfitExportService service=new ProjectProfitExportService(projects,mapper,excel,
        new CurrentUser(),new PermissionService(),Clock.fixed(Instant.parse("2026-10-23T00:00:00Z"),ZoneOffset.UTC));
    Row row() {
        return new Row(ProjectProfitApiTest.source("TK",400L,"20"),null,null,200L,320,-120L,
            null,null,null,null,null,null,"WARN",false,null,null,null,null,0,0,null,List.of());
    }
    @Test void exportCalculationReadsAllPagesWithIdenticalFilters() {
        var rows=Collections.nCopies(100,row());
        when(projects.list(anyMap())).thenAnswer(call -> {
            Map<String,String> query=call.getArgument(0);
            return new Page(query.get("page").equals("0") ? rows : List.of(row()),101,
                Integer.parseInt(query.get("page")),100,new Summary(0,0,0,0,0),"2026-10");
        });
        assertThat(service.rows(Map.of("year","2026","type","TK","q","사업"))).hasSize(101);
        verify(projects,times(2)).list(argThat(q -> q.get("type").equals("TK") && q.get("q").equals("사업") && q.get("size").equals("100")));
        verifyNoInteractions(mapper);
    }
    @Test void absentApprovedColumnsBlocksWorkbookAndAudit() {
        assertThatThrownBy(() -> service.workbook(Map.of("year","2026"),List.of()))
            .isInstanceOf(com.sicost.common.api.ApiException.class);
        verifyNoInteractions(projects,mapper);
    }
    @Test void suppliedDomainColumnBindingCreatesRealWorkbookThenDownloadAudit() throws Exception {
        when(projects.list(anyMap())).thenReturn(new Page(List.of(row()),1,0,100,new Summary(0,0,0,0,0),"2026-10"));
        var columns=List.of(new ExcelExportService.ExportColumn<Row>("cost","누적 원가",true,true,Row::cost));
        var artifact=service.workbook(Map.of("year","2026"),columns);
        var ordered=inOrder(excel,mapper);
        ordered.verify(excel).create(anyString(),anyString(),any(),eq(columns),anyList());
        ordered.verify(mapper).download(argThat(d -> d.detail().contains("\"rowCount\":1") && d.actor()==null));
        assertThat(artifact.media_type()).isEqualTo(com.sicost.common.api.DownloadArtifact.XLSX);
        try (var workbook=new XSSFWorkbook(new ByteArrayInputStream(Base64.getDecoder().decode(artifact.content_base64())))) {
            assertThat(workbook.getSheetAt(0).getRow(4).getCell(0).getNumericCellValue()).isEqualTo(320);
        }
    }
}
