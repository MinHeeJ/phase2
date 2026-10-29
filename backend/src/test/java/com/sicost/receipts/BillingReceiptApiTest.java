package com.sicost.receipts;

import static com.sicost.receipts.BillingReceiptModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.billing.*;
import com.sicost.common.billing.BillingSupportModels.*;
import com.sicost.common.persistence.AssignmentConcurrencyService;
import com.sicost.common.security.*;
import com.sicost.shared.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.test.web.servlet.*;

@WebMvcTest(controllers = BillingReceiptController.class)
@Import({RequestIdFilter.class, ApiExceptionHandler.class, BillingReceiptService.class,
    BillingMutationService.class, BillingReceivableService.class, CurrentUser.class,
    PermissionService.class, BillingReceiptApiTest.Time.class})
class BillingReceiptApiTest {
    @TestConfiguration static class Time {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-10-22T15:01:00Z"), ZoneId.of("Asia/Seoul")); }
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean BillingReceiptMapper mapper;
    @MockBean BillingMutationMapper mutations;
    @MockBean BillingRevenueQuery revenue;
    @MockBean AssignmentConcurrencyService locks;
    @MockBean SharedReadMapper shared;
    final Map<Long, ReceiptRow> rows = new LinkedHashMap<>();
    final List<Audit> audits = new ArrayList<>();
    final Set<String> closed = new HashSet<>();
    String state;
    Long original;
    long adjustment;
    long sequence;
    static final String BASE = "/api/billings/1/receipts";
    @BeforeEach void setup() {
        rows.clear(); audits.clear(); closed.clear(); sequence = 0;
        state = "CONFIRM"; original = null; adjustment = 0;
        when(mutations.state(anyLong())).thenAnswer(i -> i.getArgument(0, Long.class) == 1 ? billingState() : null);
        when(mutations.lockBill(anyLong())).thenAnswer(i -> billingState());
        when(shared.monthClose(anyString(), anyString())).thenAnswer(i -> {
            String month = i.getArgument(0);
            return List.of(new SharedReadModels.MonthClose(month, closed.contains(month) ? "CLOSED" : "OPEN"));
        });
        when(revenue.balance(anyLong())).thenAnswer(i -> balance());
        when(mapper.billing(anyLong())).thenAnswer(i -> i.getArgument(0, Long.class) == 1 ? billing() : null);
        when(mapper.details(anyLong())).thenReturn(new ArrayList<>());
        when(mapper.amendments(anyLong())).thenReturn(new ArrayList<>());
        when(mapper.history(anyLong())).thenReturn(new ArrayList<>());
        when(mapper.list(anyLong())).thenAnswer(i -> new ArrayList<>(rows.values()));
        when(mapper.row(any())).thenAnswer(i -> {
            var key = i.getArgument(0, ReceiptKey.class);
            return key.id() == 1 ? rows.get(key.receiptId()) : null;
        });
        when(mapper.insert(any())).thenAnswer(i -> {
            var w = i.getArgument(0, ReceiptWrite.class); long id = ++sequence;
            rows.put(id, new ReceiptRow(id, w.input().rcpt_dt(), w.input().rcpt_amt(), w.input().rmrk_desc()));
            return id;
        });
        when(mapper.update(any())).thenAnswer(i -> {
            var w = i.getArgument(0, ReceiptWrite.class);
            rows.put(w.receiptId(), new ReceiptRow(w.receiptId(), w.input().rcpt_dt(), w.input().rcpt_amt(), w.input().rmrk_desc()));
            return 1;
        });
        when(mapper.delete(any())).thenAnswer(i -> {
            rows.remove(i.getArgument(0, ReceiptKey.class).receiptId()); return 1;
        });
        doAnswer(i -> { audits.add(i.getArgument(0)); return null; }).when(mutations).audit(any());
    }
    State billingState() {
        return new State(1, 3, original, state, "MO", "2026-09", LocalDate.of(2026,9,1), null,
            LocalDateTime.of(2026,9,1,0,0));
    }
    Balance balance() { return new Balance(1, state, original == null ? "N" : "Y", 110, adjustment,
        rows.values().stream().mapToLong(ReceiptRow::rcpt_amt).sum()); }
    ReceiptBillingRow billing() {
        var b = balance();
        return new ReceiptBillingRow(1L, null, false, "B-2609-01", 3, "입금 시험", null, null,
            "MO", "2026-09", LocalDate.of(2026,9,1), 100, 10, 110, state, null, null,
            LocalDate.of(2026,10,1), b.received_amt(), b.adjustedTotal(), b.receivable(), 0);
    }
    ResultActions save(String method, String path, String body) throws Exception {
        return mvc.perform(request(HttpMethod.valueOf(method), path).contentType(MediaType.APPLICATION_JSON).content(body));
    }
    String input(long amount) { return "{\"rcpt_dt\":\"2026-10-23\",\"rcpt_amt\":"+amount+",\"rmrk_desc\":\"분할 입금\"}"; }
    void noWrites() {
        verify(mapper, never()).insert(any()); verify(mapper, never()).update(any());
        verify(mapper, never()).delete(any()); assertThat(audits).isEmpty();
    }
    long receipt(long amount, String date) {
        long id = ++sequence; rows.put(id, new ReceiptRow(id, LocalDate.parse(date), amount, "기존")); return id;
    }
    @Test void anonymousGetReturnsDetailAndAdjustedBalanceReadOnly() throws Exception {
        receipt(30,"2026-10-01"); adjustment = -11;
        mvc.perform(get(BASE)).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.billing.adjusted_total_amt").value(99))
            .andExpect(jsonPath("$.data.billing.receivable_amt").value(69))
            .andExpect(jsonPath("$.data.receipts[0].receipt_id").value(1))
            .andExpect(jsonPath("$.data.details").isArray()).andExpect(jsonPath("$.data.amendments").isArray())
            .andExpect(jsonPath("$.data.change_history").isArray()).andExpect(jsonPath("$.data.warnings").isArray());
        verify(mutations, never()).lockBill(anyLong()); noWrites();
    }
    @Test void anonymousCreateThirtyLeavesEightyAndPreservesRevenueMonthAndSupply() throws Exception {
        closed.add("2026-09");
        save("POST", BASE, input(30)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.billing.receivable_amt").value(80))
            .andExpect(jsonPath("$.data.billing.sply_amt").value(100))
            .andExpect(jsonPath("$.data.billing.atrb_ym").value("2026-09"));
        var order = inOrder(mutations, revenue, mapper);
        order.verify(mutations).lockBill(1); order.verify(revenue).balance(1);
        order.verify(mapper).insert(any()); order.verify(mapper).row(new ReceiptKey(1,1));
        order.verify(mutations).audit(any());
        assertThat(audits).hasSize(1); assertThat(audits.get(0).actor()).isNull();
        assertThat(audits.get(0).before()).isNull(); assertThat(audits.get(0).after()).contains("30", "2026-10-23");
        verify(shared, never()).monthClose("2026-09", "2026-09");
    }
    @Test void anonymousUpdateReplacesOnlySelectedReceiptAndAuditsAllValues() throws Exception {
        long id = receipt(30,"2026-10-01"); receipt(10,"2026-10-02"); adjustment = -11;
        save("PUT", BASE+"/"+id, input(40)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.billing.receivable_amt").value(49))
            .andExpect(jsonPath("$.data.billing.received_amt").value(50));
        assertThat(rows.get(2L).rcpt_amt()).isEqualTo(10);
        assertThat(audits.get(0).before()).contains("2026-10-01", "30", "기존");
        assertThat(audits.get(0).after()).contains("2026-10-23", "40", "분할 입금");
        assertThat(billing().sply_amt()).isEqualTo(100); assertThat(billing().atrb_ym()).isEqualTo("2026-09");
    }
    @Test void anonymousDeleteIncreasesOutstandingAndAuditsDeletedSnapshot() throws Exception {
        long id = receipt(30,"2026-10-01"); receipt(10,"2026-10-02");
        mvc.perform(delete(BASE+"/"+id)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.billing.receivable_amt").value(100));
        mvc.perform(get(BASE)).andExpect(jsonPath("$.data.receipts.length()").value(1))
            .andExpect(jsonPath("$.data.billing.received_amt").value(10));
        assertThat(audits.get(0).type()).isEqualTo("DELETE");
        assertThat(audits.get(0).before()).contains("2026-10-01", "30"); assertThat(audits.get(0).after()).isNull();
        var order = inOrder(mutations, mapper); order.verify(mutations).lockBill(1);
        order.verify(mapper).row(new ReceiptKey(1,id)); order.verify(mapper).delete(new ReceiptKey(1,id));
        order.verify(mutations).audit(any());
    }
    @ParameterizedTest @CsvSource({"POST,0", "POST,-1", "PUT,0", "PUT,-1"})
    void nonpositiveAmountIsField400AndOriginalUnchanged(String method, long amount) throws Exception {
        receipt(20,"2026-10-01"); var before = new LinkedHashMap<>(rows);
        save(method, method.equals("POST") ? BASE : BASE+"/1", input(amount))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.field").value("rcpt_amt")).andExpect(jsonPath("$.requestId").isNotEmpty());
        noWrites(); assertThat(rows).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"POST","PUT"})
    void missingDateIs400(String method) throws Exception {
        receipt(20,"2026-10-01");
        save(method, method.equals("POST") ? BASE : BASE+"/1", "{\"rcpt_amt\":30}")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("rcpt_dt")); noWrites();
    }
    @ParameterizedTest @ValueSource(strings={"1.5","9223372036854775808","\"30\"","null"})
    void finiteIntegerWonRequired(String amount) throws Exception {
        save("POST",BASE,"{\"rcpt_dt\":\"2026-10-23\",\"rcpt_amt\":"+amount+"}")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("rcpt_amt")); noWrites();
    }
    @ParameterizedTest @ValueSource(strings={"receipt_id","bill_id","created_by","tot_amt","name"})
    void immutableOrUnknownFieldsRejected(String field) throws Exception {
        var node = json.createObjectNode().put("rcpt_dt","2026-10-23").put("rcpt_amt",30).put(field,1);
        save("POST",BASE,node.toString()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field)); noWrites();
    }
    @Test void storageRemarkBoundIsField400() throws Exception {
        var node = json.createObjectNode().put("rcpt_dt","2026-10-23").put("rcpt_amt",30).put("rmrk_desc","가".repeat(201));
        save("POST",BASE,node.toString()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("rmrk_desc")); noWrites();
    }
    @ParameterizedTest @ValueSource(strings={"DRAFT","CANCEL","PLAN","AMENDMENT"})
    void createOnlyConfirmedOriginal(String value) throws Exception {
        if (value.equals("AMENDMENT")) original = 2L; else state = value;
        save("POST",BASE,input(30)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_BILL")); noWrites();
    }
    @ParameterizedTest @ValueSource(strings={"POST","PUT"})
    void confirmedAmendmentsReduceAllowedReceiptAndOverReceiptIsAtomic(String method) throws Exception {
        receipt(30,"2026-10-01"); adjustment = -55;
        save(method,method.equals("POST")?BASE:BASE+"/1",input(60))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OVER_RECEIPT"));
        noWrites(); assertThat(rows.get(1L).rcpt_amt()).isEqualTo(30);
    }
    @ParameterizedTest @ValueSource(strings={"POST","PUT","DELETE"})
    void closedReceiptMonthHasNoMutationOrAudit(String method) throws Exception {
        receipt(30,"2026-10-01"); closed.add("2026-10");
        var result = method.equals("DELETE") ? mvc.perform(delete(BASE+"/1"))
            : save(method,method.equals("POST")?BASE:BASE+"/1",input(40));
        result.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH"));
        noWrites(); assertThat(rows.get(1L).rcpt_amt()).isEqualTo(30);
    }
    @Test void updateChecksOldReceiptMonthEvenWhenProposedMonthIsOpen() throws Exception {
        receipt(30,"2026-09-01"); closed.add("2026-09");
        save("PUT",BASE+"/1",input(40)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH")); noWrites();
    }
    @Test void updateChecksNewReceiptMonthWhenOldMonthOpen() throws Exception {
        receipt(30,"2026-11-01"); closed.add("2026-10");
        save("PUT",BASE+"/1",input(40)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH")); noWrites();
    }
    @ParameterizedTest @ValueSource(strings={"PUT","DELETE"})
    void missingOrForeignReceiptCannotBeMutated(String method) throws Exception {
        receipt(20,"2026-10-01");
        var result = method.equals("DELETE") ? mvc.perform(delete(BASE+"/99")) : save("PUT",BASE+"/99",input(30));
        result.andExpect(status().isNotFound()); noWrites(); assertThat(rows.get(1L).rcpt_amt()).isEqualTo(20);
    }
    @ParameterizedTest @ValueSource(strings={"GET","POST","PUT","DELETE"})
    void missingParentIs404(String method) throws Exception {
        String path = "/api/billings/2/receipts" + (Set.of("PUT","DELETE").contains(method)?"/1":"");
        var result = Set.of("GET","DELETE").contains(method) ? mvc.perform(request(HttpMethod.valueOf(method),path)) : save(method,path,input(30));
        result.andExpect(status().isNotFound()); noWrites();
    }
    @Test void lostConditionalUpdateReturnsStale() throws Exception {
        receipt(20,"2026-10-01"); doReturn(0).when(mapper).update(any());
        save("PUT",BASE+"/1",input(30)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_BILL"));
        assertThat(audits).isEmpty(); assertThat(rows.get(1L).rcpt_amt()).isEqualTo(20);
    }
}
