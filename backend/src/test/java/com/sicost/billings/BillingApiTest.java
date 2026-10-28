package com.sicost.billings;

import static com.sicost.billings.BillingModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
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
@Import({RequestIdFilter.class, ApiExceptionHandler.class, BillingService.class, BillingAmountService.class,
    CurrentUser.class, PermissionService.class, BillingApiTest.Time.class})
class BillingApiTest {
    @TestConfiguration static class Time {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-10-22T15:01:00Z"), ZoneId.of("Asia/Seoul")); }
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean BillingMapper mapper;
    StoredBill stored;
    Project project;
    List<DetailLine> lines;
    List<Audit> audits;
    @BeforeEach void setup() {
        project = new Project(1, "MO", "MONTH", "Y");
        stored = new StoredBill(10, 1, null, 1, "2026-10", LocalDate.of(2026,10,23), 100, 10,
            "DRAFT", null, null, "N", null, null, null, LocalDateTime.of(2026,10,23,0,0));
        lines = new ArrayList<>(List.of(line(100)));
        audits = new ArrayList<>();
        when(mapper.project(1)).thenAnswer(i -> project);
        when(mapper.lockProject(1)).thenAnswer(i -> project);
        when(mapper.stored(10)).thenAnswer(i -> stored);
        when(mapper.lockBill(10)).thenAnswer(i -> stored);
        when(mapper.row(10)).thenAnswer(i -> row());
        when(mapper.details(10)).thenAnswer(i -> List.copyOf(lines));
        when(mapper.receipts(10)).thenReturn(List.of());
        when(mapper.amendments(10)).thenReturn(List.of());
        when(mapper.history(10)).thenReturn(List.of());
        when(mapper.sources(any())).thenReturn(List.of(source(100L, true)));
        when(mapper.stage(any())).thenAnswer(i -> {
            var key = i.getArgument(0, StageKey.class);
            return key.stageId() == 3 ? new Stage(3, 1, 1, 100, LocalDate.of(2026,10,23)) : null;
        });
        when(mapper.nextSerial(any())).thenReturn(1);
        when(mapper.insert(any())).thenAnswer(i -> { apply(i.getArgument(0)); return 10L; });
        when(mapper.update(any())).thenAnswer(i -> { apply(i.getArgument(0)); return 1; });
        doAnswer(i -> { lines.clear(); return null; }).when(mapper).deleteDetails(10);
        doAnswer(i -> {
            var write = i.getArgument(0, DetailMonth.class);
            lines = new ArrayList<>(lines.stream().map(line -> new DetailLine(line.asgn_id(), line.dev_id(),
                line.cntrct_dev_id(), line.display_name(), write.month(), line.mm_qty(), line.source_mm_qty(),
                line.untprc_amt(), line.work_day_qty(), line.work_tm_qty(), line.ovtm_tm_qty(),
                line.ovtm_amt(), line.bill_amt(), line.rmrk_desc())).toList());
            return null;
        }).when(mapper).moveDetails(any());
        doAnswer(i -> { lines.add(i.getArgument(0, DetailWrite.class).line()); return null; })
            .when(mapper).insertDetail(any());
        doAnswer(i -> { audits.add(i.getArgument(0)); return null; }).when(mapper).audit(any());
    }
    DetailLine line(long amount) {
        return new DetailLine(2L, 4L, 4L, "시험 인력", "2026-10", BigDecimal.ONE, BigDecimal.ONE,
            amount, null, null, null, 0, amount, null);
    }
    SourceLine source(Long price, boolean attendance) {
        return new SourceLine(2, 4, 4L, "시험 인력", "NEW", BigDecimal.ONE, price,
            attendance ? 5L : null, null, new BigDecimal("8"), BigDecimal.ZERO, new BigDecimal("20"));
    }
    BillingRow row() {
        return new BillingRow(10L, stored.bill_stg_id(), false, "B-2610-01", 1, "청구 시험", null, null,
            project.cntrct_ty_cd(), stored.atrb_ym(), stored.bill_dt(), stored.sply_amt(), stored.vat_amt(),
            stored.sply_amt()+stored.vat_amt(), stored.bill_stts_cd(), stored.txinv_no(), stored.txinv_dt(),
            stored.rcpt_plan_dt(), 0, stored.sply_amt()+stored.vat_amt(),
            "CONFIRM".equals(stored.bill_stts_cd()) ? stored.sply_amt()+stored.vat_amt() : 0, null);
    }
    void apply(BillWrite write) {
        var f = write.fields(); var k = write.identity();
        stored = new StoredBill(10, k.projectId(), k.stageId(), k.ordinal(), k.month(), f.bill_dt(),
            write.money().supply(), write.money().vat(), "DRAFT", f.txinv_no(), f.txinv_dt(), "N", null,
            f.rcpt_plan_dt(), f.rmrk_desc(), stored.created_at());
    }
    ResultActions save(String method, String body) throws Exception {
        return mvc.perform(request(org.springframework.http.HttpMethod.valueOf(method),
            method.equals("POST") ? "/api/billings" : "/api/billings/10")
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }
    @Test void anonymousMonthlyCreatePersistsDraftGeneratedKeyDetailsAndNullActorAudit() throws Exception {
        save("POST", "{\"project_id\":1,\"month\":\"2026-10\"}")
            .andExpect(status().isOk()).andExpect(header().exists("X-Request-Id"))
            .andExpect(jsonPath("$.data.billing.bill_stts_cd").value("DRAFT"))
            .andExpect(jsonPath("$.data.details[0].bill_amt").value(100))
            .andExpect(jsonPath("$.data.billing.receivable_amt").value(0));
        var order = inOrder(mapper);
        order.verify(mapper).lockNumber("B-2610-");
        order.verify(mapper).lockProject(1);
        order.verify(mapper).insert(any());
        order.verify(mapper).insertDetail(argThat(d -> d.id()==10));
        assertThat(audits).allMatch(a -> a.actor()==null);
        verify(mapper).insert(argThat(w -> w.number().equals("B-2610-01")));
    }
    @Test void anonymousTurnkeyCreateNeedsStageAndPersistsStageSnapshot() throws Exception {
        project = new Project(1, "TK", "STAGE", "Y");
        save("POST", "{\"project_id\":1,\"stage_id\":3}")
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.billing.bill_stg_id").value(3))
            .andExpect(jsonPath("$.data.details[0].work_ym").value("2026-10"));
    }
    @Test void internalProjectIsValidationErrorWithoutWritesOrAuthentication() throws Exception {
        project = new Project(1, "IN", null, "Y");
        save("POST", "{\"project_id\":1,\"month\":\"2026-10\"}")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verify(mapper, never()).insert(any());
    }
    @ParameterizedTest @ValueSource(strings={
        "{\"project_id\":1,\"month\":\"2026-13\"}",
        "{\"project_id\":1}", "{\"project_id\":0}",
        "{\"project_id\":1,\"month\":\"2026-10\",\"sply_amt\":-1}",
        "{\"project_id\":1,\"month\":\"2026-10\",\"vat_amt\":-1}",
        "{\"project_id\":1,\"month\":\"2026-10\",\"sply_amt\":1.5}",
        "{\"project_id\":1,\"month\":\"2026-10\",\"tot_amt\":20}"})
    void invalidCreationDoesNotInsert(String body) throws Exception {
        save("POST", body).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR")).andExpect(jsonPath("$.field").isNotEmpty());
        verify(mapper, never()).insert(any());
    }
    @Test void foreignTurnkeyStageIsFieldError() throws Exception {
        project = new Project(1, "TK", "STAGE", "Y");
        save("POST", "{\"project_id\":1,\"stage_id\":999}")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("stage_id"));
    }
    @Test void uncancelledTurnkeyDuplicateReturnsExistingBillGuidance() throws Exception {
        project = new Project(1, "TK", "STAGE", "Y");
        when(mapper.existingStage(any())).thenReturn(1);
        save("POST", "{\"project_id\":1,\"stage_id\":3}")
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_BILLED"));
        verify(mapper, never()).insert(any());
    }
    @Test void duplicateMonthRequiresExplicitAdditional() throws Exception {
        when(mapper.maxOrdinal(any())).thenReturn(1);
        save("POST", "{\"project_id\":1,\"month\":\"2026-10\"}")
            .andExpect(status().isConflict()).andExpect(jsonPath("$.field").value("additional"));
    }
    @Test void additionalRequiresIncreasingOrdinal() throws Exception {
        when(mapper.maxOrdinal(any())).thenReturn(2);
        save("POST", "{\"project_id\":1,\"month\":\"2026-10\",\"additional\":true,\"bill_odr_no\":2}")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("bill_odr_no"));
    }
    @Test void explicitAdditionalIncreasingOrdinalSucceeds() throws Exception {
        when(mapper.maxOrdinal(any())).thenReturn(2);
        save("POST", "{\"project_id\":1,\"month\":\"2026-10\",\"additional\":true,\"bill_odr_no\":3}")
            .andExpect(status().isOk());
        verify(mapper).insert(argThat(w -> w.identity().ordinal()==3));
    }
    @Test void missingPriceBlocksAutomaticGeneration() throws Exception {
        when(mapper.sources(any())).thenReturn(List.of(source(null, true)));
        save("POST", "{\"project_id\":1,\"month\":\"2026-10\"}")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("untprc_amt"));
    }
    @Test void missingAttendanceWarnsAndExcludesPerson() throws Exception {
        project = new Project(1, "DP", "HOUR", "Y");
        when(mapper.sources(any())).thenReturn(List.of(source(100L, false)));
        save("POST", "{\"project_id\":1,\"month\":\"2026-10\"}")
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.details").isEmpty())
            .andExpect(jsonPath("$.data.warnings[1]").value(org.hamcrest.Matchers.containsString("근태 미입력")));
    }
    @Test void hourBillingAllowsAbsentWorkDaysAndManualOvertimeWithoutMultiplier() throws Exception {
        project = new Project(1, "DP", "HOUR", "Y");
        save("POST", "{\"project_id\":1,\"month\":\"2026-10\",\"details\":[{\"asgn_id\":2,\"ovtm_amt\":17}]}")
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.details[0].bill_amt").value(817))
            .andExpect(jsonPath("$.data.details[0].mm_qty").isEmpty());
    }
    @Test void anonymousUpdatePreservesManualOverrideAndDetailsUntilExplicitRecalculation() throws Exception {
        when(mapper.sources(any())).thenReturn(List.of(source(200L, true)));
        save("PUT", "{\"sply_amt\":77,\"vat_amt\":9}").andExpect(status().isOk())
            .andExpect(jsonPath("$.data.billing.sply_amt").value(77))
            .andExpect(jsonPath("$.data.billing.vat_amt").value(9))
            .andExpect(jsonPath("$.data.details[0].bill_amt").value(100))
            .andExpect(jsonPath("$.data.warnings[0]").value(org.hamcrest.Matchers.containsString("상세 합")));
        verify(mapper, never()).sources(any());
        assertThat(audits).anyMatch(a -> a.column().equals("sply_amt") && a.before().equals("100") && a.after().equals("77"));
    }
    @Test void explicitRecalculationRegeneratesSourcesButKeepsExplicitSupplyOverride() throws Exception {
        when(mapper.sources(any())).thenReturn(List.of(source(200L, true)));
        save("PUT", "{\"recalculate\":true,\"sply_amt\":77}").andExpect(status().isOk())
            .andExpect(jsonPath("$.data.details[0].bill_amt").value(200))
            .andExpect(jsonPath("$.data.billing.sply_amt").value(77));
    }
    @ParameterizedTest @ValueSource(strings={"{\"bill_dt\":\"2026-02-30\"}",
        "{\"sply_amt\":-1}", "{\"vat_amt\":-1}", "{\"vat_amt\":1.2}",
        "{\"tot_amt\":99}", "{\"project_id\":2}", "{\"month\":\"2026-11\"}"})
    void invalidUpdatesPreserveOriginal(String body) throws Exception {
        var before = stored;
        save("PUT", body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(stored).isEqualTo(before);
        verify(mapper, never()).update(any());
    }
    @Test void confirmedOriginalIsImmutable() throws Exception {
        stored = new StoredBill(10,1,null,1,"2026-10",stored.bill_dt(),100,10,"CONFIRM",null,null,
            "N",null,null,null,stored.created_at());
        var before = stored;
        save("PUT", "{\"sply_amt\":77,\"recalculate\":true}").andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("STALE_BILL"));
        assertThat(stored).isEqualTo(before);
        verify(mapper, never()).deleteDetails(anyLong());
    }
    @Test void closedOldMonthCannotBeEscapedByNewDate() throws Exception {
        project = new Project(1,"TK","STAGE","Y");
        when(mapper.monthStatus("2026-10")).thenReturn("CLOSED");
        save("PUT", "{\"bill_dt\":\"2026-11-01\"}").andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CLOSED_MONTH"));
        verify(mapper, never()).update(any());
    }
    @Test void closedNewMonthCannotBeEntered() throws Exception {
        project = new Project(1,"TK","STAGE","Y");
        when(mapper.monthStatus("2026-11")).thenReturn("CLOSED");
        save("PUT", "{\"bill_dt\":\"2026-11-01\"}").andExpect(status().isConflict());
    }
    @Test void pastYearWritesRejectedAtApiBoundary() throws Exception {
        save("POST", "{\"project_id\":1,\"month\":\"2025-12\"}")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
    @Test void turnkeyDateMoveAlignsHeaderAndDetailWithoutSourceRecalculation() throws Exception {
        project = new Project(1,"TK","STAGE","Y");
        save("PUT", "{\"bill_dt\":\"2026-11-01\"}").andExpect(status().isOk())
            .andExpect(jsonPath("$.data.billing.atrb_ym").value("2026-11"))
            .andExpect(jsonPath("$.data.details[0].work_ym").value("2026-11"));
        verify(mapper, never()).sources(any());
    }
    @Test void detailIncludesReceiptsAmendmentLinksHistoryAndSnapshot() throws Exception {
        when(mapper.receipts(10)).thenReturn(List.of(new Receipt(7,LocalDate.of(2026,11,2),11,"일부 입금")));
        when(mapper.history(10)).thenReturn(List.of(new History("UPDATE sply_amt",LocalDateTime.of(2026,10,23,0,0),null)));
        mvc.perform(get("/api/billings/10")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.receipts[0].receipt_id").value(7))
            .andExpect(jsonPath("$.data.change_history[0].description").value("UPDATE sply_amt"))
            .andExpect(jsonPath("$.data.details[0].bill_amt").value(100));
    }
    @Test void filteredPagingUsesSameFilterForRowsAndTotalWithoutOwnershipNarrowing() throws Exception {
        when(mapper.list(any())).thenReturn(List.of(row()));
        when(mapper.count(any())).thenReturn(1L);
        mvc.perform(get("/api/billings").param("year","2026").param("status","DRAFT").param("q","청구")
            .param("page","0").param("size","20").param("sort","bill_no,asc"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.page").value(0));
        var captor = org.mockito.ArgumentCaptor.forClass(BillingFilter.class);
        verify(mapper).list(captor.capture());
        verify(mapper).count(captor.getValue());
        assertThat(captor.getValue().offset()).isZero();
        assertThat(captor.getValue().q()).isEqualTo("청구");
    }
    @Test void unknownIdReturnsNotFoundWithoutLeakingStorage() throws Exception {
        mvc.perform(get("/api/billings/999")).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
    @Test void unsafeSortRejectedBeforeMapper() throws Exception {
        mvc.perform(get("/api/billings").param("sort","bill_no;drop table,asc"))
            .andExpect(status().isBadRequest());
        verify(mapper, never()).list(any());
    }
}
