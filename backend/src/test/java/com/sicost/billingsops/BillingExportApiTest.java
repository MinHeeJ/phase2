package com.sicost.billingsops;

import com.sicost.common.api.*;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.security.*;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import static com.sicost.billingsops.BillingModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers=BillingExportController.class)
@Import({RequestIdFilter.class,ApiExceptionHandler.class,ExcelExportService.class,CurrentUser.class})
class BillingExportApiTest {
    @Autowired MockMvc mvc;
    @MockBean BillingGapService gaps;
    @MockBean BillingReceivableService receivables;
    @MockBean BillingMapper mapper;
    @MockBean PermissionService permissions;
    @MockBean Clock clock;
    static final String XLSX="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @BeforeEach void setup() {
        when(clock.withZone(any())).thenReturn(Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"),ZoneId.of("Asia/Seoul")));
        when(gaps.filtered(any())).thenReturn(List.of(new Gap(1,"계약 프로젝트","2026-10",100,0,100,
            BigDecimal.ZERO,"청구 없음","청구 확인",false,List.of(),List.of())));
        when(receivables.filtered(any())).thenReturn(List.of());
    }
    @Test void anonymousGapExportReturnsWorkbookAndOneDownloadSideEffect() throws Exception {
        var response=mvc.perform(get("/api/billings/gaps/export").param("month","2026-10"))
            .andExpect(status().isOk()).andExpect(content().contentType(XLSX))
            .andExpect(header().string("Content-Disposition",org.hamcrest.Matchers.containsString("attachment")))
            .andReturn().getResponse();
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            assertThat(book.getSheetAt(0).getRow(4).getCell(0).getStringCellValue()).isEqualTo("계약 프로젝트");
            assertThat(book.getSheetAt(0).getRow(4).getCell(4).getNumericCellValue()).isEqualTo(100);
        }
        verify(gaps).filtered(argThat(q->"2026-10".equals(q.month())));
        verify(mapper).download(argThat(d->d.actor()==null && d.description().equals("{\"rows\":1}")));
        verifyNoMoreInteractions(mapper);
    }
    @Test void anonymousOverdueExportReturnsEmptyWorkbookAndOneDownloadSideEffect() throws Exception {
        var response=mvc.perform(get("/api/billings/overdue/export").param("year","2026"))
            .andExpect(status().isOk()).andExpect(content().contentType(XLSX))
            .andExpect(header().exists("Content-Disposition")).andReturn().getResponse();
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            assertThat(book.getSheetAt(0).getRow(3).getCell(0).getStringCellValue()).isEqualTo("고객사");
            assertThat((Object) book.getSheetAt(0).getRow(4)).isNull();
        }
        verify(receivables).filtered(argThat(q->q.year()==2026));
        verify(mapper).download(argThat(d->d.actor()==null && d.description().equals("{\"rows\":0}")));
        verifyNoMoreInteractions(mapper);
    }
    @Test void invalidGapMonthDoesNotQueryOrAudit() throws Exception {
        mvc.perform(get("/api/billings/gaps/export").param("month","2026-13"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("month"))
            .andExpect(jsonPath("$.requestId").isNotEmpty());
        verifyNoInteractions(gaps,mapper);
    }
    @Test void invalidOverdueStageDoesNotQueryOrAudit() throws Exception {
        mvc.perform(get("/api/billings/overdue/export").param("stage","4"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("stage"))
            .andExpect(jsonPath("$.requestId").isNotEmpty());
        verifyNoInteractions(receivables,mapper);
    }
}
