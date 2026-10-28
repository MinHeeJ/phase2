package com.sicost.receipts;

import static com.sicost.receipts.BillingReceiptModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.test.web.servlet.*;

@WebMvcTest(controllers = BillingReceiptController.class)
@Import({RequestIdFilter.class, ApiExceptionHandler.class, BillingReceiptErrors.class,
    BillingReceiptService.class, CurrentUser.class, PermissionService.class})
class BillingReceiptApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired CurrentUser currentUser;
    @MockBean BillingReceiptMapper mapper;
    final Map<Long, Receipt> rows = new LinkedHashMap<>();
    final List<ReceiptAudit> audits = new ArrayList<>();
    final Set<String> closed = new HashSet<>();
    Root root;
    long sequence;
    long adjustment;
    static final String BASE = "/api/billings/1/receipts";

    @BeforeEach void setup() {
        rows.clear(); audits.clear(); closed.clear(); sequence = 0; adjustment = 0;
        root = new Root(1, 3, "CONFIRM", "N", null);
        when(mapper.root(anyLong())).thenAnswer(i -> i.getArgument(0, Long.class) == 1 ? root : null);
        when(mapper.lockRoot(1)).thenAnswer(i -> root);
        when(mapper.lockProject(3)).thenReturn(3L);
        when(mapper.monthStatus(anyString())).thenAnswer(i -> closed.contains(i.getArgument(0)) ? "CLOSED" : null);
        when(mapper.billing(1)).thenAnswer(i -> {
            long received = rows.values().stream().mapToLong(Receipt::rcpt_amt).sum();
            return new HashMap<String, Object>(Map.of("bill_id", 1L, "bill_no", "R-TEST",
                "sply_amt", 100L, "vat_amt", 10L, "tot_amt", 110L, "atrb_ym", "2026-01",
                "received_amt", received, "adjusted_total_amt", 110L + adjustment,
                "receivable_amt", 110L + adjustment - received));
        });
        when(mapper.receipts(1)).thenAnswer(i -> new ArrayList<>(rows.values()));
        when(mapper.receipt(any())).thenAnswer(i -> {
            var key = i.getArgument(0, ReceiptKey.class);
            return key.id() == 1 ? rows.get(key.receiptId()) : null;
        });
        when(mapper.insert(any())).thenAnswer(i -> {
            var w = i.getArgument(0, ReceiptWrite.class);
            long key = ++sequence;
            rows.put(key, new Receipt(key, w.row().rcpt_dt(), w.row().rcpt_amt(), w.row().rmrk_desc()));
            return key;
        });
        when(mapper.update(any())).thenAnswer(i -> {
            var w = i.getArgument(0, ReceiptWrite.class);
            rows.put(w.row().receipt_id(), w.row()); return 1;
        });
        when(mapper.delete(any())).thenAnswer(i -> {
            rows.remove(i.getArgument(0, ReceiptKey.class).receiptId()); return 1;
        });
        doAnswer(i -> { audits.add(i.getArgument(0)); return null; }).when(mapper).audit(any());
    }
    Map<String, Object> input() {
        return new LinkedHashMap<>(Map.of("rcpt_dt", "2026-10-23", "rcpt_amt", 30, "rmrk_desc", "분할 입금"));
    }
    ResultActions save(String method, Map<String, Object> body) throws Exception {
        return mvc.perform(request(HttpMethod.valueOf(method), method.equals("POST") ? BASE : BASE + "/1")
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }
    void seed() { rows.put(1L, new Receipt(1L, LocalDate.of(2026, 10, 23), 30, "기존")); sequence = 1; }
    void unchanged() {
        verify(mapper, never()).insert(any()); verify(mapper, never()).update(any());
        verify(mapper, never()).delete(any()); assertThat(audits).isEmpty();
    }
    @Test void anonymousGetReturnsReceiptsAndAdjustedBalanceWithoutWrites() throws Exception {
        seed(); adjustment = -11;
        mvc.perform(get(BASE)).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.receipts[0].receipt_id").value(1))
            .andExpect(jsonPath("$.data.billing.receivable_amt").value(69))
            .andExpect(jsonPath("$.data.details").isArray()).andExpect(jsonPath("$.data.amendments").isArray())
            .andExpect(jsonPath("$.data.change_history").isArray()).andExpect(jsonPath("$.data.warnings").isArray());
        unchanged(); verify(mapper, never()).lockProject(anyLong());
    }
    @Test void anonymousPostKeepsRevenueAndAuditsUnderProjectThenRootLock() throws Exception {
        closed.add("2026-01");
        mvc.perform(post("/api/billings/1/receipts").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(input())))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.billing.receivable_amt").value(80))
            .andExpect(jsonPath("$.data.billing.sply_amt").value(100))
            .andExpect(jsonPath("$.data.billing.atrb_ym").value("2026-01"));
        var order = inOrder(mapper);
        order.verify(mapper).lockProject(3); order.verify(mapper).lockRoot(1);
        order.verify(mapper).insert(any()); order.verify(mapper).audit(any());
        assertThat(audits).hasSize(1); assertThat(audits.get(0).actor()).isEqualTo(currentUser.userId());
        assertThat(audits.get(0).before()).isNull();
        assertThat(json.readTree(audits.get(0).after()).path("bill_id").asLong()).isEqualTo(1);
        mvc.perform(get(BASE)).andExpect(jsonPath("$.data.billing.received_amt").value(30));
    }
    @Test void anonymousPutRecalculatesAdjustedBalanceAndKeepsBeforeAfter() throws Exception {
        seed(); adjustment = -11;
        var body = input(); body.put("rcpt_amt", 40); body.put("rcpt_dt", "2026-11-01");
        body.put("receipt_id", 999);
        mvc.perform(put("/api/billings/1/receipts/1").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.receipts[0].receipt_id").value(1))
            .andExpect(jsonPath("$.data.billing.receivable_amt").value(59))
            .andExpect(jsonPath("$.data.billing.sply_amt").value(100))
            .andExpect(jsonPath("$.data.billing.atrb_ym").value("2026-01"));
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).before()).contains("2026-10-23", "30", "기존");
        assertThat(audits.get(0).after()).contains("2026-11-01", "40", "분할 입금");
        assertThat(audits.get(0).actor()).isEqualTo(currentUser.userId());
    }
    @Test void anonymousDeleteIncreasesBalanceAndAuditsDeletedValues() throws Exception {
        seed(); rows.put(2L, new Receipt(2L, LocalDate.of(2026, 10, 24), 20, null));
        mvc.perform(delete("/api/billings/1/receipts/1")).andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.billing.received_amt").value(20))
            .andExpect(jsonPath("$.data.billing.receivable_amt").value(90))
            .andExpect(jsonPath("$.data.billing.sply_amt").value(100));
        assertThat(rows).containsOnlyKeys(2L); assertThat(audits).hasSize(1);
        assertThat(audits.get(0).type()).isEqualTo("DELETE");
        assertThat(audits.get(0).actor()).isEqualTo(currentUser.userId());
        assertThat(audits.get(0).before()).contains("2026-10-23", "30");
        assertThat(audits.get(0).after()).isNull();
        mvc.perform(get(BASE)).andExpect(jsonPath("$.data.billing.receivable_amt").value(90));
    }
    @ParameterizedTest @CsvSource({"POST,rcpt_dt", "POST,rcpt_amt"})
    void createRequiresField(String method, String field) throws Exception {
        var body = input(); body.remove(field);
        save(method, body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.field").value(field)).andExpect(jsonPath("$.requestId").isNotEmpty());
        unchanged();
    }
    @ParameterizedTest @CsvSource({"POST,0", "POST,-1", "PUT,0", "PUT,-1"})
    void rejectsNonpositiveAmountsWithoutChanges(String method, long amount) throws Exception {
        seed(); var before = rows.get(1L); var body = input(); body.put("rcpt_amt", amount);
        save(method, body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("rcpt_amt"));
        unchanged(); assertThat(rows.get(1L)).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings = {"POST", "PUT"})
    void explicitNullDateRejected(String method) throws Exception {
        seed(); var body = input(); body.put("rcpt_dt", null);
        save(method, body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("rcpt_dt"));
        unchanged();
    }
    @ParameterizedTest @ValueSource(strings = {"POST", "PUT"})
    void rejectsExcessReceiptAgainstConfirmedAmendments(String method) throws Exception {
        seed(); adjustment = -11; var body = input(); body.put("rcpt_amt", 100);
        save(method, body).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OVER_RECEIPT"));
        unchanged(); assertThat(rows.get(1L).rcpt_amt()).isEqualTo(30);
    }
    @ParameterizedTest @CsvSource({"POST,DRAFT,N", "POST,CANCEL,N", "POST,PLAN,N", "POST,CONFIRM,Y",
        "PUT,DRAFT,N", "PUT,CANCEL,N", "PUT,CONFIRM,Y", "DELETE,DRAFT,N", "DELETE,CANCEL,N", "DELETE,CONFIRM,Y"})
    void mutationsRequireConfirmedOriginal(String method, String status, String amendment) throws Exception {
        seed(); root = new Root(1, 3, status, amendment, amendment.equals("Y") ? 8L : null);
        var result = method.equals("DELETE") ? mvc.perform(delete(BASE + "/1")) : save(method, input());
        result.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_BILL")); unchanged();
    }
    @ParameterizedTest @ValueSource(strings = {"POST", "PUT", "DELETE"})
    void closedOldMonthNeverChangesReceiptOrHistory(String method) throws Exception {
        seed(); closed.add("2026-10"); var before = rows.get(1L);
        var body = input(); body.put("rcpt_dt", "2026-11-01");
        if (method.equals("POST")) closed.add("2026-11");
        var result = method.equals("DELETE") ? mvc.perform(delete(BASE + "/1")) : save(method, body);
        result.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH"));
        unchanged();
        assertThat(rows.get(1L)).isEqualTo(before);
    }
    @Test void putChecksNewMonthEvenWhenOldMonthOpen() throws Exception {
        seed(); closed.add("2026-11"); var body = input(); body.put("rcpt_dt", "2026-11-01");
        save("PUT", body).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH")); unchanged();
    }
    @ParameterizedTest @ValueSource(strings = {"PUT", "DELETE"})
    void missingOrOtherParentsReceiptDoesNotWrite(String method) throws Exception {
        var result = method.equals("DELETE") ? mvc.perform(delete(BASE + "/99"))
            : mvc.perform(put(BASE + "/99").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input())));
        result.andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("receiptId")); unchanged();
    }
    @ParameterizedTest @ValueSource(strings = {"GET", "POST", "PUT", "DELETE"})
    void nonexistentOriginalIsBusinessValidationNotAuthentication(String method) throws Exception {
        String path = "/api/billings/99/receipts" + (Set.of("PUT", "DELETE").contains(method) ? "/1" : "");
        mvc.perform(request(HttpMethod.valueOf(method), path).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(input()))).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR")); unchanged();
    }
    @ParameterizedTest @ValueSource(strings = {"1.5", "9223372036854775808", "null", "\"30\""})
    void fractionalOverflowNullAndTextAmountsRejected(String value) throws Exception {
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
            .content("{\"rcpt_dt\":\"2026-10-23\",\"rcpt_amt\":" + value + "}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR")); unchanged();
    }
    @ParameterizedTest @ValueSource(strings = {"{", "{} {}", "[]", ""})
    void malformedBodiesUseBillingErrorEnvelope(String body) throws Exception {
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR")); unchanged();
    }
    @Test void invalidPathUsesBillingErrorCode() throws Exception {
        mvc.perform(get("/api/billings/no/receipts")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR")); unchanged();
    }
    @Test void partialPutPreservesOmittedValuesAndNullClearsOnlyNote() throws Exception {
        seed(); var body = new HashMap<String, Object>(); body.put("rmrk_desc", null);
        save("PUT", body).andExpect(status().isOk()).andExpect(jsonPath("$.data.receipts[0].rcpt_dt").value("2026-10-23"))
            .andExpect(jsonPath("$.data.receipts[0].rcpt_amt").value(30));
        assertThat(rows.get(1L).rmrk_desc()).isNull();
    }
}
