package com.sicost.billingsops;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.security.*;
import java.io.ByteArrayInputStream;
import java.time.*;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import static com.sicost.billingsops.BillingReviewModels.*;

class BillingReviewDownloadTest {
    @Test void filteredExportTraversesAllPagesAndProducesRealXlsxAndDownloadAudit() throws Exception {
        var review=mock(BillingReviewService.class);var mapper=mock(BillingReviewMapper.class);
        var gap=new GapRow(3,"=not a formula","2026-10",100,0,100,new java.math.BigDecimal("10000"),"청구 없음","검증",false,List.of(),List.of());
        when(review.gaps(any())).thenAnswer(i->{Map<String,String> q=i.getArgument(0);int page=Integer.parseInt(q.get("page"));
            assertThat(q).containsEntry("judgment","청구 없음").containsEntry("size","100");
            return new GapResult("2026-10",100,List.of(gap),page==0?2:2,page,100);});
        var service=new BillingReviewDownloadService(review,mapper,new ExcelExportService(),new CurrentUser(),
            new PermissionService(),Clock.fixed(Instant.parse("2026-10-23T00:00:00Z"),ZoneId.of("Asia/Seoul")));
        var result=service.gaps(Map.of("judgment","청구 없음","page","9"));
        assertThat(result.media_type()).contains("spreadsheetml.sheet");
        try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(Base64.getDecoder().decode(result.content_base64())))) {
            assertThat(workbook.getSheetAt(0).getLastRowNum()).isEqualTo(5);
            var cell=workbook.getSheetAt(0).getRow(4).getCell(0);
            assertThat(cell.getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.STRING);
            assertThat(cell.getStringCellValue()).isEqualTo("=not a formula");
        }
        verify(review,times(2)).gaps(any());
        verify(mapper).access(argThat(a->a.actor()==null && a.description().contains("\"rowCount\":2")));
    }
}
