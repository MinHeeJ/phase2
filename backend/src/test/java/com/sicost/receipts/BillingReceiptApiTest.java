package com.sicost.receipts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.*;
import static com.sicost.receipts.BillingReceiptModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = BillingReceiptController.class)
@Import({RequestIdFilter.class, ApiExceptionHandler.class, BillingReceiptService.class,
    BillingReceivableService.class, CurrentUser.class, PermissionService.class})
class BillingReceiptApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean BillingReceiptMapper mapper;
    @MockBean Clock clock;
    final Map<Long, Receipt> rows = new LinkedHashMap<>();
    final List<ReceiptAudit> audits = new ArrayList<>();
    String state;
    boolean amendment;
    long adjusted;

    Header header() {
        return new Header(1, null, "B-2610-01", 1, "프로젝트", null, null, "MO",
            "2026-09", LocalDate.of(2026, 9, 1), 100, 10, 110, state,
            null, null, LocalDate.of(2026, 10, 1), amendment ? "Y" : "N", amendment ? 2L : null);
    }
    Receipt receipt(long id, String date, long amount) {
        return new Receipt(id, LocalDate.parse(date), amount, "분할 입금");
    }
    @BeforeEach void setup() {
        rows.clear(); audits.clear(); state = "CONFIRM"; amendment = false; adjusted = 110;
        when(clock.withZone(any())).thenReturn(Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"), ZoneId.of("Asia/Seoul")));
        when(mapper.header(1)).thenAnswer(i -> header());
        when(mapper.lockBill(1)).thenAnswer(i -> new BillState(1, state, amendment ? "Y" : "N", amendment ? 2L : null));
        when(mapper.totals(1)).thenAnswer(i -> new Totals(BigDecimal.valueOf(adjusted),
            rows.values().stream().map(r -> BigDecimal.valueOf(r.rcpt_amt())).reduce(BigDecimal.ZERO, BigDecimal::add)));
        when(mapper.list(1)).thenAnswer(i -> new ArrayList<>(rows.values()));
        when(mapper.monthStatus("2026-09")).thenReturn("CLOSED");
        when(mapper.row(any())).thenAnswer(i -> {
            var key = i.getArgument(0, ReceiptKey.class);
            return key.billId() == 1 ? rows.get(key.receiptId()) : null;
        });
        when(mapper.insert(any())).thenAnswer(i -> {
            var w = i.getArgument(0, ReceiptWrite.class);
            rows.put(8L, new Receipt(8, w.input().rcpt_dt(), w.input().rcpt_amt(), w.input().rmrk_desc()));
            return 8L;
        });
        when(mapper.update(any())).thenAnswer(i -> {
            var w = i.getArgument(0, ReceiptWrite.class);
            rows.put(w.receiptId(), new Receipt(w.receiptId(), w.input().rcpt_dt(), w.input().rcpt_amt(), w.input().rmrk_desc()));
            return 1;
        });
        when(mapper.delete(any())).thenAnswer(i -> {
            rows.remove(i.getArgument(0, ReceiptKey.class).receiptId()); return 1;
        });
        doAnswer(i -> { audits.add(i.getArgument(0)); return null; }).when(mapper).audit(any());
    }
    Map<String, Object> body() {
        return new LinkedHashMap<>(Map.of("rcpt_dt", "2026-10-15", "rcpt_amt", 30, "rmrk_desc", "입금"));
    }
    ResultActions write(String method, Object input) throws Exception {
        var builder = switch (method) {
            case "POST" -> post("/api/billings/1/receipts");
            case "PUT" -> put("/api/billings/1/receipts/7");
            default -> delete("/api/billings/1/receipts/7");
        };
        if (!method.equals("DELETE")) builder.contentType("application/json").content(json.writeValueAsString(input));
        return mvc.perform(builder);
    }
    void untouched() {
        verify(mapper, never()).insert(any()); verify(mapper, never()).update(any());
        verify(mapper, never()).delete(any()); verify(mapper, never()).audit(any());
    }
    @Test void anonymousListReturnsDetailEnvelopeAndAdjustedBalanceWithoutWrites() throws Exception {
        rows.put(7L, receipt(7, "2026-10-10", 30)); adjusted = 88;
        mvc.perform(get("/api/billings/1/receipts")).andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.receipts[0].receipt_id").value(7))
            .andExpect(jsonPath("$.data.billing.adjusted_total_amt").value(88))
            .andExpect(jsonPath("$.data.billing.receivable_amt").value(58))
            .andExpect(jsonPath("$.data.details").isArray()).andExpect(jsonPath("$.data.change_history").isArray());
        untouched(); verify(mapper, never()).lockBill(anyLong());
    }
    @Test void anonymousCreateThirtyLeavesEightyAndPreservesSupplyAndRevenueMonth() throws Exception {
        write("POST", body()).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.billing.receivable_amt").value(80))
            .andExpect(jsonPath("$.data.billing.sply_amt").value(100))
            .andExpect(jsonPath("$.data.billing.atrb_ym").value("2026-09"));
        var order = inOrder(mapper); order.verify(mapper).lockBill(1); order.verify(mapper).header(1);
        order.verify(mapper).monthStatus("2026-10"); order.verify(mapper).header(1); order.verify(mapper).totals(1);
        order.verify(mapper).insert(any()); order.verify(mapper).row(new ReceiptKey(1, 8)); order.verify(mapper).audit(any());
        assertThat(audits).hasSize(1); assertThat(audits.get(0).actor()).isNull();
        assertThat(audits.get(0).before()).isNull();
        assertThat(json.readTree(audits.get(0).after()).get("rcpt_amt").asLong()).isEqualTo(30);
    }
    @Test void anonymousUpdateReplacesOnlySelectedReceiptAndKeepsFullBeforeAfter() throws Exception {
        rows.put(7L, receipt(7, "2026-10-10", 20)); rows.put(6L, receipt(6, "2026-10-09", 10)); adjusted = 88;
        write("PUT", body()).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.billing.received_amt").value(40))
            .andExpect(jsonPath("$.data.billing.receivable_amt").value(48))
            .andExpect(jsonPath("$.data.billing.sply_amt").value(100))
            .andExpect(jsonPath("$.data.billing.atrb_ym").value("2026-09"));
        var audit = audits.get(0);
        assertThat(audit.receiptId()).isEqualTo("7"); assertThat(audit.billId()).isEqualTo("1");
        assertThat(json.readTree(audit.before()).get("rcpt_dt").asText()).isEqualTo("2026-10-10");
        assertThat(json.readTree(audit.before()).get("rcpt_amt").asLong()).isEqualTo(20);
        assertThat(json.readTree(audit.after()).get("rcpt_dt").asText()).isEqualTo("2026-10-15");
        assertThat(json.readTree(audit.after()).get("rmrk_desc").asText()).isEqualTo("입금");
        assertThat(rows.get(6L).rcpt_amt()).isEqualTo(10);
    }
    @Test void anonymousDeleteIncreasesRemainingBalanceAndAuditsDeletedValues() throws Exception {
        rows.put(7L, receipt(7, "2026-10-10", 30)); rows.put(6L, receipt(6, "2026-10-09", 10));
        write("DELETE", null).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.billing.received_amt").value(10))
            .andExpect(jsonPath("$.data.billing.receivable_amt").value(100));
        mvc.perform(get("/api/billings/1/receipts")).andExpect(jsonPath("$.data.receipts.length()").value(1));
        assertThat(audits.get(0).type()).isEqualTo("DELETE"); assertThat(audits.get(0).after()).isNull();
        assertThat(json.readTree(audits.get(0).before()).get("rcpt_amt").asLong()).isEqualTo(30);
    }
    @ParameterizedTest @CsvSource({"POST,0", "POST,-1", "PUT,0", "PUT,-1"})
    void rejectsNonPositiveAmountsWithoutMutation(String method, long amount) throws Exception {
        rows.put(7L, receipt(7, "2026-10-10", 20)); var input = body(); input.put("rcpt_amt", amount);
        write(method, input).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.field").value("rcpt_amt")); untouched(); assertThat(rows.get(7L).rcpt_amt()).isEqualTo(20);
    }
    @ParameterizedTest @ValueSource(strings = {"POST", "PUT"})
    void missingDateIs400AndKeepsOldReceipt(String method) throws Exception {
        rows.put(7L, receipt(7, "2026-10-10", 20)); var input = body(); input.remove("rcpt_dt");
        write(method, input).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("rcpt_dt")); untouched();
    }
    @ParameterizedTest @CsvSource({"rcpt_amt,1.5", "rcpt_amt,9223372036854775808", "rcpt_dt,2026-02-30"})
    void malformedWonAndDatesAreExplicitValidationErrors(String field, String value) throws Exception {
        var input = json.valueToTree(body());
        ((com.fasterxml.jackson.databind.node.ObjectNode) input).set(field,
            field.equals("rcpt_amt") ? json.readTree(value) : json.valueToTree(value));
        write("POST", input).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR")); untouched();
    }
    @ParameterizedTest @ValueSource(strings = {"POST", "PUT"})
    void overReceiptUsesAdjustedConfirmedTotal(String method) throws Exception {
        rows.put(7L, receipt(7, "2026-10-10", 20)); adjusted = 25;
        write(method, body()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OVER_RECEIPT")); untouched();
    }
    @ParameterizedTest @ValueSource(strings = {"PLAN", "DRAFT", "CANCEL"})
    void nonConfirmedOriginalCannotReceiveCash(String status) throws Exception {
        state = status; write("POST", body()).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("STALE_BILL")); untouched();
    }
    @ParameterizedTest @ValueSource(strings = {"POST", "PUT", "DELETE"})
    void amendmentCannotReceiveOrMutateCash(String method) throws Exception {
        amendment = true; rows.put(7L, receipt(7, "2026-10-10", 20));
        write(method, body()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_BILL")); untouched();
    }
    @ParameterizedTest @ValueSource(strings = {"PUT", "DELETE"})
    void closedOriginalReceiptCannotMoveOrBeDeleted(String method) throws Exception {
        rows.put(7L, receipt(7, "2026-09-10", 20));
        write(method, body()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH"));
        untouched(); assertThat(rows.get(7L).rcpt_dt()).hasToString("2026-09-10");
    }
    @ParameterizedTest @ValueSource(strings = {"POST", "PUT"})
    void closedTargetMonthIsIndependentOfBillMonth(String method) throws Exception {
        rows.put(7L, receipt(7, "2026-10-10", 20)); var input = body(); input.put("rcpt_dt", "2026-09-15");
        write(method, input).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH")); untouched();
    }
    @ParameterizedTest @ValueSource(strings = {"PUT", "DELETE"})
    void receiptFromAnotherBillOrMissingReceiptIsNotFound(String method) throws Exception {
        rows.put(9L, receipt(9, "2026-10-10", 20));
        write(method, body()).andExpect(status().isNotFound()); untouched(); assertThat(rows).containsKey(9L);
    }
    @ParameterizedTest @ValueSource(strings = {"POST", "PUT", "DELETE"})
    void missingBillIsNotFoundWithoutMutation(String method) throws Exception {
        when(mapper.lockBill(1)).thenReturn(null); write(method, body()).andExpect(status().isNotFound()); untouched();
    }
    @Test void bodyIdentityCannotOverrideSelectedPath() throws Exception {
        var input = body(); input.put("receipt_id", 9);
        write("PUT", input).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("receipt_id")); untouched();
    }
    @Test void missingCloseRowAllowsPastYearApiWrite() throws Exception {
        var input = body(); input.put("rcpt_dt", "2025-12-01"); write("POST", input).andExpect(status().isOk());
    }
    @Test void nullBodyAndInvalidPathReturnValidationError() throws Exception {
        mvc.perform(post("/api/billings/1/receipts")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(get("/api/billings/not-a-number/receipts")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR")); untouched();
    }
    @Test void conditionalUpdateFailureIsStaleAndDoesNotAudit() throws Exception {
        rows.put(7L, receipt(7, "2026-10-10", 20)); doReturn(0).when(mapper).update(any());
        write("PUT", body()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_BILL"));
        verify(mapper, never()).audit(any());
    }
}
