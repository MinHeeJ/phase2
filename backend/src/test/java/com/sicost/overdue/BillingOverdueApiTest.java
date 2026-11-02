package com.sicost.overdue;

import com.sicost.billingsops.*;
import com.sicost.common.api.*;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.security.*;
import java.io.ByteArrayInputStream;
import java.time.*;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

@WebMvcTest(controllers={BillingController.class,BillingExportController.class})
@Import({RequestIdFilter.class,ApiExceptionHandler.class,BillingReceivableService.class,
    CurrentUser.class,PermissionService.class,ExcelExportService.class})
class BillingOverdueApiTest {
    @Autowired MockMvc mvc;
    @MockBean BillingMapper mapper;
    @MockBean BillingAmountService amounts;
    @MockBean BillingGapService gaps;
    @MockBean BillingRevenueQuery revenue;
    @MockBean BillingCommandService commands;
    @MockBean Clock clock;
    @BeforeEach void setup() {
        when(clock.withZone(any())).thenReturn(Clock.fixed(Instant.parse("2026-10-30T00:00:00Z"),ZoneId.of("Asia/Seoul")));
        when(mapper.states(2026)).thenReturn(List.of(row(7,"CONFIRM",60,0,"N"),row(8,"CONFIRM",7,0,"N")));
    }
    State row(long id,String status,Integer days,long received,String amendment) {
        return new State(id,"B-"+id,1,null,"2026-10",LocalDate.of(2026,10,1),1000,100,1100,
            status,null,null,days==null?null:LocalDate.of(2026,10,30).minusDays(days),amendment,null,null,null,
            "청구 프로젝트",2L,"고객사","MO","영업파트","N",received,1100);
    }
    @Test void anonymousListUsesSameFilteredSetForPagingTotalsAndCustomerSummary() throws Exception {
        mvc.perform(get("/api/billings/overdue").param("year","2026").param("stage","1").param("size","20").param("customerId","2").param("q","청구"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].billing.bill_id").value(8))
            .andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.page").value(0))
            .andExpect(jsonPath("$.data.customer_totals[0].receivable_amt").value(1100));
        verify(mapper,never()).transition(any());verify(mapper,never()).audit(any());verify(mapper,never()).download(any());
    }
    @ParameterizedTest @CsvSource({"6,0","7,1","29,1","30,2","59,2","60,3"})
    void exactStageBoundaries(int days,int stage) throws Exception {
        when(mapper.states(2026)).thenReturn(List.of(row(7,"CONFIRM",days,0,"N")));
        mvc.perform(get("/api/billings/overdue").param("year","2026"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].stage").value(stage));
    }
    @Test void missingDueDateDoesNotInventDaysOrNotification() throws Exception {
        when(mapper.states(2026)).thenReturn(List.of(row(7,"CONFIRM",null,0,"N")));
        mvc.perform(get("/api/billings/overdue").param("year","2026"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].due_status").value("예정일 미입력"))
            .andExpect(jsonPath("$.data.items[0].overdue_days").isEmpty()).andExpect(jsonPath("$.data.items[0].next_notification_date").isEmpty());
    }
    @Test void settledDraftCancelledAndAmendmentsAreNotReceivables() throws Exception {
        when(mapper.states(2026)).thenReturn(List.of(row(1,"CONFIRM",60,1100,"N"),row(2,"DRAFT",60,0,"N"),
            row(3,"CANCEL",60,0,"N"),row(4,"CONFIRM",60,0,"Y")));
        mvc.perform(get("/api/billings/overdue").param("year","2026"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty()).andExpect(jsonPath("$.data.total").value(0));
    }
    @Test void partialReceiptReducesBalanceAndRolesStayCumulative() throws Exception {
        when(mapper.states(2026)).thenReturn(List.of(row(7,"CONFIRM",60,400,"N")));
        mvc.perform(get("/api/billings/overdue").param("year","2026"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].billing.receivable_amt").value(700))
            .andExpect(jsonPath("$.data.items[0].recipient_roles").value(org.hamcrest.Matchers.containsInAnyOrder("영업 담당자","PM","영업 파트장","경영진","재무")));
    }
    @Test void overDaysIsStrictAndCustomerFilterIsAppliedToTotal() throws Exception {
        when(mapper.states(2026)).thenReturn(List.of(row(7,"CONFIRM",30,0,"N")));
        mvc.perform(get("/api/billings/overdue").param("year","2026").param("overDays","30"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/billings/overdue").param("year","2026").param("customerId","99"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
    }
    @Test void nextReminderIsBasedOnOriginalDueDate() throws Exception {
        when(mapper.states(2026)).thenReturn(List.of(row(7,"CONFIRM",7,0,"N")));
        mvc.perform(get("/api/billings/overdue").param("year","2026"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].next_notification_date").value("2026-11-22"));
    }
    @ParameterizedTest @CsvSource({"stage,4","stage,-1","overDays,7","year,0","customerId,-1","page,-1","size,101","sort,untrusted"})
    void listValidationDoesNotReadDatabase(String field,String value) throws Exception {
        mvc.perform(get("/api/billings/overdue").param(field,value))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field));
        verifyNoInteractions(mapper);
    }
    @Test void exportUsesAllMatchingRowsNotCurrentPageAndIsRealWorkbook() throws Exception {
        var rows=new ArrayList<State>();
        for(int i=1;i<=21;i++) rows.add(row(i,"CONFIRM",60,0,"N"));
        when(mapper.states(2026)).thenReturn(rows);
        byte[] bytes=mvc.perform(get("/api/billings/overdue/export").param("year","2026").param("size","20"))
            .andExpect(status().isOk()).andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .andReturn().getResponse().getContentAsByteArray();
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(book.getSheetAt(0).getRow(3).getCell(0).getStringCellValue()).isEqualTo("고객사");
            assertThat(book.getSheetAt(0).getRow(4).getCell(2).getStringCellValue()).isEqualTo("B-1");
            assertThat(book.getSheetAt(0).getRow(24).getCell(2).getStringCellValue()).isEqualTo("B-21");
        }
        verify(mapper).download(argThat(d->d.actor()==null && d.description().equals("{\"rows\":21}")));
        verify(mapper,never()).transition(any());
    }
    @Test void exportFiltersMatchListFilters() throws Exception {
        byte[] bytes=mvc.perform(get("/api/billings/overdue/export").param("year","2026").param("stage","1"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(book.getSheetAt(0).getRow(4).getCell(2).getStringCellValue()).isEqualTo("B-8");
            assertThat((Object) book.getSheetAt(0).getRow(5)).isNull();
        }
    }
    @Test void invalidExportDoesNotWriteDownloadAudit() throws Exception {
        mvc.perform(get("/api/billings/overdue/export").param("stage","4"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("stage"));
        verifyNoInteractions(mapper);
    }
    @Test void unexpectedFailureDoesNotExposeSql() throws Exception {
        when(mapper.states(2026)).thenThrow(new IllegalStateException("private SQL"));
        mvc.perform(get("/api/billings/overdue").param("year","2026"))
            .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.message").value("서버 오류가 발생했습니다."));
    }
}
