package com.sicost.billingsops;

import com.sicost.common.api.*;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.security.*;
import java.io.ByteArrayInputStream;
import java.time.*;
import java.util.List;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers=BillingExportController.class)
@Import({RequestIdFilter.class,ApiExceptionHandler.class,ExcelExportService.class,CurrentUser.class,PermissionService.class})
class BillingExportApiTest {
    @Autowired MockMvc mvc;
    @MockBean BillingGapService gaps;
    @MockBean BillingReceivableService receivables;
    @MockBean BillingMapper mapper;
    @MockBean Clock clock;

    @BeforeEach void setup() {
        when(clock.withZone(any())).thenReturn(Clock.fixed(Instant.parse("2026-10-30T00:00:00Z"),ZoneId.of("Asia/Seoul")));
        when(gaps.filtered(any())).thenReturn(List.of());
        when(receivables.filtered(any())).thenReturn(List.of());
    }
    @Test void gapsExportAnonymousHappyHasWorkbookAndDownloadSideEffect() throws Exception {
        byte[] bytes=mvc.perform(get("/api/billings/gaps/export").param("month","2026-10"))
            .andExpect(status().isOk())
            .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .andExpect(header().string("Content-Disposition",org.hamcrest.Matchers.containsString("attachment")))
            .andReturn().getResponse().getContentAsByteArray();
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(book.getNumberOfSheets()).isEqualTo(1);
            assertThat(book.getSheetAt(0).getRow(3).getCell(0).getStringCellValue()).isEqualTo("프로젝트");
        }
        verify(gaps).filtered(argThat(q->q.month().equals("2026-10")));
        verify(mapper).download(argThat(d->d.actor()==null&&d.description().equals("{\"rows\":0}")));
        verify(mapper,never()).transition(any());
    }
    @Test void overdueExportAnonymousHappyHasWorkbookAndDownloadSideEffect() throws Exception {
        byte[] bytes=mvc.perform(get("/api/billings/overdue/export"))
            .andExpect(status().isOk())
            .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .andReturn().getResponse().getContentAsByteArray();
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(book.getNumberOfSheets()).isEqualTo(1);
            assertThat(book.getSheetAt(0).getRow(3).getCell(0).getStringCellValue()).isEqualTo("고객사");
        }
        verify(receivables).filtered(any());
        verify(mapper).download(argThat(d->d.actor()==null&&d.description().equals("{\"rows\":0}")));
        verify(mapper,never()).transition(any());
    }
    @Test void gapsExportValidationDoesNotQueryOrAudit() throws Exception {
        mvc.perform(get("/api/billings/gaps/export").param("month","2026-13"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.field").value("month")).andExpect(jsonPath("$.requestId").isNotEmpty());
        verifyNoInteractions(gaps,receivables,mapper);
    }
    @Test void overdueExportValidationDoesNotQueryOrAudit() throws Exception {
        mvc.perform(get("/api/billings/overdue/export").param("sort","invalid,asc"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("sort"))
            .andExpect(jsonPath("$.requestId").isNotEmpty());
        verifyNoInteractions(gaps,receivables,mapper);
    }
}
