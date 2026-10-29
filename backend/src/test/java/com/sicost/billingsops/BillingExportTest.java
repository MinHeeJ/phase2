package com.sicost.billingsops;

import com.sicost.common.api.BillingPaging;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.security.*;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import static com.sicost.billingsops.BillingModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class BillingExportTest {
    @Test void filteredExportIncludesAllRowsBeyondPageAsRealXlsxAndDownloadAudit()throws Exception {
        BillingMapper mapper=mock(BillingMapper.class);BillingGapService gaps=mock(BillingGapService.class);
        BillingReceivableService receivables=mock(BillingReceivableService.class);
        Clock clock=Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"),ZoneId.of("Asia/Seoul"));
        var controller=new BillingExportController(gaps,receivables,new ExcelExportService(),new CurrentUser(),mapper,clock);
        List<Gap> rows=new ArrayList<>();
        for(int i=0;i<55;i++)rows.add(new Gap(i,"=HYPERLINK(\"https://invalid\")","2026-10",100000,0,100000,
            new BigDecimal("10000"),"청구 없음","점검",false,List.of(),List.of()));
        when(gaps.filtered(any())).thenReturn(rows);
        var response=controller.exportBillingGaps(Map.of("month","2026-10","judgment","청구 없음","sort","project_name,asc"));
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(response.getHeaders().getFirst("Content-Disposition")).contains("attachment",".xlsx");
        try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(response.getBody()))) {
            var sheet=workbook.getSheetAt(0);assertThat(sheet.getLastRowNum()).isEqualTo(58);
            assertThat(sheet.getRow(4).getCell(0).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.STRING);
            assertThat(sheet.getRow(58).getCell(2).getNumericCellValue()).isEqualTo(100000);
        }
        verify(mapper).download(argThat(d->d.actor()==null && d.description().contains("55")));
        verify(gaps).filtered(new GapQuery("2026-10","청구 없음",null,new BillingPaging(0,50,"project_name,asc")));
    }
}
