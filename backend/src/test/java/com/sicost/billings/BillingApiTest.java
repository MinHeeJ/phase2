package com.sicost.billings;

import static com.sicost.billings.BillingModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.billing.*;
import com.sicost.common.billing.BillingSupportModels.State;
import com.sicost.common.security.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.*;

@WebMvcTest(controllers = BillingController.class)
@Import({RequestIdFilter.class, ApiExceptionHandler.class, BillingService.class,
    CurrentUser.class, PermissionService.class, BillingApiTest.Time.class})
class BillingApiTest {
    @TestConfiguration static class Time {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-10-31T15:01:00Z"), ZoneId.of("Asia/Seoul")); }
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean BillingMapper mapper;
    @MockBean BillingMutationService mutations;
    @MockBean BillingAmountService amounts;
    Map<Long, Header> headers = new LinkedHashMap<>();
    Map<Long, List<DetailLine>> lines = new LinkedHashMap<>();
    String type, status;
    long sequence;
    @BeforeEach void setup() {
        headers.clear(); lines.clear(); sequence = 0; type = "TK"; status = "DRAFT";
        when(mapper.project(anyLong())).thenAnswer(i -> new Project(i.getArgument(0), type, "MONTH", "Y"));
        when(mapper.stage(7)).thenReturn(new Stage(7, 1, 1, "착수금", 1000, LocalDate.of(2026, 10, 31), LocalDateTime.of(2026, 10, 1, 0, 0)));
        when(mapper.duplicates(any())).thenReturn(new ArrayList<>());
        when(mutations.nextNumber()).thenReturn("B-2611-01");
        when(mapper.insert(any())).thenAnswer(i -> {
            Write write = i.getArgument(0); long id = ++sequence; Header h = write.header();
            headers.put(id, new Header(id, h.prj_id(), h.bill_stg_id(), h.bill_odr_no(), h.atrb_ym(), h.bill_dt(),
                h.sply_amt(), h.vat_amt(), h.txinv_no(), h.txinv_dt(), h.rcpt_plan_dt(), h.rmrk_desc(), h.amnd_yn(), h.orgnl_bill_id()));
            lines.put(id, new ArrayList<>()); return id;
        });
        when(mapper.header(anyLong())).thenAnswer(i -> headers.get(i.getArgument(0, Long.class)));
        when(mapper.row(anyLong())).thenAnswer(i -> row(i.getArgument(0)));
        when(mapper.details(anyLong())).thenAnswer(i -> lines.getOrDefault(i.getArgument(0, Long.class), new ArrayList<>()));
        doAnswer(i -> { DetailWrite w = i.getArgument(0); lines.get(w.billId()).add(w.line()); return null; }).when(mapper).insertDetail(any());
        doAnswer(i -> { lines.put(i.getArgument(0), new ArrayList<>()); return null; }).when(mapper).deleteDetails(anyLong());
        when(mapper.update(any())).thenAnswer(i -> { Write w = i.getArgument(0); headers.put(w.header().bill_id(), w.header()); return 1; });
        when(mutations.lock(anyLong())).thenAnswer(i -> {
            Header h = headers.get(i.getArgument(0, Long.class));
            if (h == null) throw ApiException.notFound("청구가 없습니다.");
            return new State(h.bill_id(), h.prj_id(), h.orgnl_bill_id(), status, type, h.atrb_ym(), h.bill_dt(),
                LocalDate.of(2026, 10, 31), LocalDateTime.of(2026, 11, 1, 0, 1));
        });
        when(amounts.suggestedVat(anyLong(), anyString())).thenAnswer(i -> i.getArgument(0, Long.class) / 10);
        when(amounts.total(anyLong(), anyLong(), anyBoolean())).thenAnswer(i -> {
            long s = i.getArgument(0), v = i.getArgument(1); boolean amendment = i.getArgument(2);
            if (amendment ? s >= 0 || v > 0 : s < 0 || v < 0) throw BillingRules.invalid(s < 0 ? "sply_amt" : "vat_amt");
            return BillingRules.add(s, v, "vat_amt");
        });
        when(amounts.lineAmount(anyLong(), any(), anyBoolean())).thenAnswer(i -> i.getArgument(2, Boolean.class) ? 0L : BillingRules.won(BigDecimal.valueOf(i.getArgument(0, Long.class)).multiply(i.getArgument(1)), "sply_amt"));
        when(mapper.sources(any())).thenReturn(List.of(new Source(9, 3, 3, "시험 인력", "NEW", new BigDecimal("1.25"), 1000L, null, null, null, null, null)));
    }
    BillingRow row(long id) {
        Header h = headers.get(id); if (h == null) return null;
        return new BillingRow(id, h.bill_stg_id(), false, "B-2611-01", h.prj_id(), "청구 프로젝트", null,
            null, type, h.atrb_ym(), h.bill_dt(), h.sply_amt(), h.vat_amt(), h.sply_amt() + h.vat_amt(),
            status, h.txinv_no(), h.txinv_dt(), h.rcpt_plan_dt(), 0, h.sply_amt() + h.vat_amt(), 0, null);
    }
    ResultActions write(String method, String path, String body) throws Exception {
        return mvc.perform(request(org.springframework.http.HttpMethod.valueOf(method), path).contentType(MediaType.APPLICATION_JSON).content(body));
    }
    void create() throws Exception { write("POST", "/api/billings", "{\"project_id\":1,\"stage_id\":7}").andExpect(status().isOk()); }
    @Test void anonymousCreateTkUsesCreationMonthNumberAndNoNullMonthPersonDetail() throws Exception {
        create();
        verify(mapper).insert(argThat(w -> w.number().equals("B-2611-01") && w.header().sply_amt() == 1000 && w.actor() == null));
        verify(mapper, never()).insertDetail(any());
        verify(mutations).requireOpen(YearMonth.of(2026, 10), "month");
        verify(mutations, atLeastOnce()).audit(any());
        mvc.perform(get("/api/billings/1")).andExpect(status().isOk()).andExpect(jsonPath("$.data.billing.bill_dt").isEmpty())
            .andExpect(jsonPath("$.data.details").isArray()).andExpect(jsonPath("$.data.warnings").isNotEmpty());
    }
    @ParameterizedTest @ValueSource(strings={"B-2611-02", "B-2611-99", "B-2611-100"})
    void createPreservesTheSharedAllocatedNumber(String number) throws Exception {
        when(mutations.nextNumber()).thenReturn(number);
        create();
        verify(mapper).insert(argThat(write -> write.number().equals(number)));
    }
    @ParameterizedTest @ValueSource(strings={"MO","DP"})
    void monthlyCreateStoresHeaderBeforeSourceDetail(String contract) throws Exception {
        type = contract;
        write("POST", "/api/billings", "{\"project_id\":1,\"month\":\"2026-10\"}").andExpect(status().isOk())
            .andExpect(jsonPath("$.data.billing.sply_amt").value(1250)).andExpect(jsonPath("$.data.details[0].work_ym").value("2026-10"));
        var order = inOrder(mapper); order.verify(mapper).project(1); order.verify(mapper).duplicates(any());
        order.verify(mapper).sources(any()); order.verify(mapper).insert(any()); order.verify(mapper).insertDetail(any());
    }
    @Test void anonymousInternalProjectRejectedWithoutInsert() throws Exception {
        type = "IN"; write("POST", "/api/billings", "{\"project_id\":1,\"month\":\"2026-10\"}")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verify(mapper, never()).insert(any());
    }
    @Test void absentStageIsFieldError() throws Exception {
        write("POST", "/api/billings", "{\"project_id\":1}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("stage_id"));
        verify(mapper, never()).insert(any());
    }
    @Test void foreignStageIsFieldError() throws Exception {
        when(mapper.stage(8)).thenReturn(new Stage(8, 2, 1, "다른 프로젝트", 100, null, null));
        write("POST", "/api/billings", "{\"project_id\":1,\"stage_id\":8}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("stage_id"));
        verify(mapper, never()).insert(any());
    }
    @ParameterizedTest @ValueSource(strings={"2026-13", "2026-1", "0000-01"})
    void invalidMonthNeverWrites(String month) throws Exception {
        type = "MO"; write("POST", "/api/billings", "{\"project_id\":1,\"month\":\"" + month + "\"}")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("month")); verify(mapper, never()).insert(any());
    }
    @ParameterizedTest @ValueSource(strings={"-1", "1.1", "9223372036854775808", "\"100\""})
    void invalidCreateAmount(String value) throws Exception {
        write("POST", "/api/billings", "{\"project_id\":1,\"stage_id\":7,\"sply_amt\":"+value+"}")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR")); verify(mapper, never()).insert(any());
    }
    @Test void duplicateTkStageReturnsExistingBillingGuidance() throws Exception {
        create(); when(mapper.duplicates(any())).thenReturn(List.of(headers.get(1L)));
        write("POST", "/api/billings", "{\"project_id\":1,\"stage_id\":7}").andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ALREADY_BILLED")).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("기존 청구 #1")));
        assertThat(headers).hasSize(1);
    }
    @Test void duplicateMonthlyRequiresFlag() throws Exception {
        type = "MO"; write("POST", "/api/billings", "{\"project_id\":1,\"month\":\"2026-10\"}");
        when(mapper.duplicates(any())).thenReturn(List.of(headers.get(1L)));
        write("POST", "/api/billings", "{\"project_id\":1,\"month\":\"2026-10\",\"bill_odr_no\":2}").andExpect(status().isConflict());
        assertThat(headers).hasSize(1);
    }
    @Test void additionalRequiresIncreasingOrder() throws Exception {
        type = "MO"; write("POST", "/api/billings", "{\"project_id\":1,\"month\":\"2026-10\"}");
        when(mapper.duplicates(any())).thenReturn(List.of(headers.get(1L)));
        write("POST", "/api/billings", "{\"project_id\":1,\"month\":\"2026-10\",\"additional\":true,\"bill_odr_no\":1}").andExpect(status().isConflict());
        write("POST", "/api/billings", "{\"project_id\":1,\"month\":\"2026-10\",\"additional\":true,\"bill_odr_no\":2}").andExpect(status().isOk());
    }
    @Test void missingPriceBlocksEvenWithManualSupply() throws Exception {
        type = "MO"; when(mapper.sources(any())).thenReturn(List.of(new Source(9,3,3,"단가 없음","NEW",BigDecimal.ONE,null,null,null,null,null,null)));
        write("POST", "/api/billings", "{\"project_id\":1,\"month\":\"2026-10\",\"sply_amt\":100}").andExpect(status().isBadRequest());
        verify(mapper, never()).insert(any());
    }
    @Test void missingAttendanceWarnsAndExcludes() throws Exception {
        type = "DP"; when(mapper.project(1)).thenReturn(new Project(1,"DP","DAY","Y"));
        write("POST", "/api/billings", "{\"project_id\":1,\"month\":\"2026-10\"}").andExpect(status().isOk())
            .andExpect(jsonPath("$.data.details").isEmpty()).andExpect(jsonPath("$.data.warnings[1]").value(org.hamcrest.Matchers.containsString("근태")));
    }
    @Test void ordinaryPutPreservesExplicitSupplyVatAndSourceSnapshot() throws Exception {
        create(); clearInvocations(mapper);
        write("PUT", "/api/billings/1", "{\"sply_amt\":999,\"vat_amt\":9,\"txinv_no\":\"수동번호\"}").andExpect(status().isOk())
            .andExpect(jsonPath("$.data.billing.sply_amt").value(999)).andExpect(jsonPath("$.data.billing.vat_amt").value(9));
        verify(mapper, never()).sources(any()); verify(mapper, never()).deleteDetails(anyLong());
        verify(mutations).audit(argThat(a -> a.field().equals("sply_amt") && "1000".equals(a.before()) && "999".equals(a.after())));
    }
    @Test void explicitRecalculationOnlyReplacesDetailsAndPreservesVat() throws Exception {
        type = "MO"; write("POST", "/api/billings", "{\"project_id\":1,\"month\":\"2026-10\",\"vat_amt\":7}");
        clearInvocations(mapper);
        write("PUT", "/api/billings/1", "{\"recalculate\":true,\"sply_amt\":1200}").andExpect(status().isOk())
            .andExpect(jsonPath("$.data.billing.sply_amt").value(1200)).andExpect(jsonPath("$.data.billing.vat_amt").value(7));
        verify(mapper).sources(any()); verify(mapper).deleteDetails(1); assertThat(lines.get(1L)).hasSize(1);
    }
    @ParameterizedTest @ValueSource(strings={"CONFIRM","CANCEL","PLAN"})
    void nonDraftCannotBeUpdated(String state) throws Exception {
        create(); status = state; Header old = headers.get(1L); clearInvocations(mapper);
        write("PUT", "/api/billings/1", "{\"sply_amt\":2000}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_BILL"));
        verify(mapper, never()).update(any()); assertThat(headers.get(1L)).isEqualTo(old);
    }
    @Test void closedMonthCannotBeUpdated() throws Exception {
        create(); doThrow(ApiException.conflict("CLOSED_MONTH","마감", "month")).when(mutations).requireOpen(any(State.class));
        clearInvocations(mapper); write("PUT", "/api/billings/1", "{\"sply_amt\":2000}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH"));
        verify(mapper, never()).update(any()); verify(mapper, never()).deleteDetails(anyLong());
    }
    @Test void changingTkBillDateChecksBothOldAndNewEffectiveMonths() throws Exception {
        create(); write("PUT", "/api/billings/1", "{\"bill_dt\":\"2026-12-01\"}").andExpect(status().isOk());
        verify(mutations).requireOpen(any(State.class)); verify(mutations).requireOpen(YearMonth.of(2026,12),"bill_dt");
    }
    @ParameterizedTest @ValueSource(strings={"{\"bill_dt\":\"2026-02-30\"}", "{\"vat_amt\":-1}", "{\"sply_amt\":-1}", "{\"tot_amt\":200}", "{\"project_id\":2}", "{\"details\":[]}"})
    void invalidPutNeverChangesDetails(String body) throws Exception {
        create(); clearInvocations(mapper); write("PUT", "/api/billings/1", body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verify(mapper, never()).update(any()); verify(mapper, never()).deleteDetails(anyLong());
    }
    @Test void listPassesSameValidatedFiltersToCountAndList() throws Exception {
        when(mapper.list(any())).thenReturn(new ArrayList<>()); when(mapper.count(any())).thenReturn(3L);
        mvc.perform(get("/api/billings?year=2026&type=MO&customerId=4&q=조회&status=DRAFT&page=0&size=20&sort=sply_amt,asc"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(3)).andExpect(jsonPath("$.data.page").value(0));
        var capture = org.mockito.ArgumentCaptor.forClass(Query.class); verify(mapper).list(capture.capture()); verify(mapper).count(capture.getValue());
        assertThat(capture.getValue().customerId()).isEqualTo(4);
    }
    @Test void invalidSortIsFieldError() throws Exception {
        mvc.perform(get("/api/billings?sort=drop,asc")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("sort"));
        verify(mapper, never()).list(any());
    }
    @ParameterizedTest @ValueSource(strings={"project_id","stage_id","month"})
    void confirmedIdentityChangesAreStaleRatherThanApplied(String field) throws Exception {
        create(); status = "CONFIRM"; Header before = headers.get(1L); clearInvocations(mapper);
        String value = field.equals("month") ? "\"2026-12\"" : "2";
        write("PUT", "/api/billings/1", "{\""+field+"\":"+value+"}")
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_BILL"));
        verify(mapper, never()).update(any()); assertThat(headers.get(1L)).isEqualTo(before);
    }
    @Test void yearZeroIsRejectedBeforeDatabase() throws Exception {
        write("POST", "/api/billings", "{\"project_id\":1,\"stage_id\":7,\"bill_dt\":\"0000-01-01\"}")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("bill_dt"));
        verify(mapper, never()).insert(any());
    }
    @Test void amountOverflowIsFieldErrorWithoutWrite() throws Exception {
        write("POST", "/api/billings", "{\"project_id\":1,\"stage_id\":7,\"sply_amt\":9223372036854775807,\"vat_amt\":1}")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("vat_amt"));
        verify(mapper, never()).insert(any());
    }
    @Test void closedCreateDoesNotAllocateNumberOrInsert() throws Exception {
        doThrow(ApiException.conflict("CLOSED_MONTH", "마감", "month")).when(mutations).requireOpen(YearMonth.of(2026,10), "month");
        write("POST", "/api/billings", "{\"project_id\":1,\"stage_id\":7}")
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH"));
        verify(mutations, never()).nextNumber(); verify(mapper, never()).insert(any());
    }
    @Test void proposedClosedTkMonthPreservesHeader() throws Exception {
        create(); Header before = headers.get(1L); clearInvocations(mapper);
        doThrow(ApiException.conflict("CLOSED_MONTH", "마감", "bill_dt")).when(mutations).requireOpen(YearMonth.of(2026,12), "bill_dt");
        write("PUT", "/api/billings/1", "{\"bill_dt\":\"2026-12-01\"}").andExpect(status().isConflict());
        verify(mapper, never()).update(any()); assertThat(headers.get(1L)).isEqualTo(before);
    }
    @Test void detailIncludesStoredSnapshotsReceiptsAmendmentsAndAudit() throws Exception {
        create(); status = "CONFIRM";
        when(mapper.receipts(1)).thenReturn(List.of(new Receipt(5,LocalDate.of(2026,11,5),110,"입금")));
        when(mapper.history(1)).thenReturn(List.of(new History("sply_amt: 1000 → 999",LocalDateTime.of(2026,11,1,0,1),null)));
        when(mapper.amendments(1)).thenReturn(List.of(row(1)));
        mvc.perform(get("/api/billings/1")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.receipts[0].receipt_id").value(5))
            .andExpect(jsonPath("$.data.receipts[0].rcpt_dt").value("2026-11-05"))
            .andExpect(jsonPath("$.data.amendments[0].bill_id").value(1))
            .andExpect(jsonPath("$.data.change_history[0].description").value("sply_amt: 1000 → 999"));
        verify(mapper, never()).sources(any());
    }
    @Test void missingDetailIs404() throws Exception { mvc.perform(get("/api/billings/99")).andExpect(status().isNotFound()); }
}
