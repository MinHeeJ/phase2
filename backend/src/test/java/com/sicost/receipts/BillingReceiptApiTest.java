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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.*;

@WebMvcTest(controllers = BillingReceiptController.class)
@Import({RequestIdFilter.class, ApiExceptionHandler.class, BillingReceiptService.class,
    CurrentUser.class, PermissionService.class, BillingReceiptApiTest.Time.class})
class BillingReceiptApiTest {
    @TestConfiguration static class Time {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-10-22T15:01:00Z"), ZoneId.of("Asia/Seoul")); }
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean BillingReceiptMapper mapper;
    final Map<Long,ReceiptRow> rows = new LinkedHashMap<>();
    final List<ReceiptAudit> audits = new ArrayList<>();
    BillState bill;
    long sequence;
    static final String BASE="/api/billings/1/receipts";
    @BeforeEach void setup() {
        rows.clear(); audits.clear(); sequence=0;
        bill=new BillState(1,"CONFIRM","N",null,110);
        when(mapper.bill(anyLong())).thenAnswer(i->i.getArgument(0,Long.class)==1?bill:null);
        when(mapper.lockBill(anyLong())).thenAnswer(i->i.getArgument(0,Long.class)==1?bill:null);
        when(mapper.receipt(any())).thenAnswer(i->{var k=i.getArgument(0,ReceiptKey.class);return k.id()==1?rows.get(k.receiptId()):null;});
        when(mapper.receipts(anyLong())).thenAnswer(i->new ArrayList<>(rows.values()));
        when(mapper.receivedExcluding(any())).thenAnswer(i->{var k=i.getArgument(0,ReceiptKey.class);return rows.values().stream().filter(r->r.receipt_id()!=k.receiptId()).mapToLong(ReceiptRow::rcpt_amt).sum();});
        when(mapper.insert(any())).thenAnswer(i->{var w=i.getArgument(0,ReceiptWrite.class);long key=++sequence;rows.put(key,new ReceiptRow(key,w.input().rcpt_dt(),w.input().rcpt_amt(),w.input().rmrk_desc()));return key;});
        when(mapper.update(any())).thenAnswer(i->{var w=i.getArgument(0,ReceiptWrite.class);rows.put(w.receiptId(),new ReceiptRow(w.receiptId(),w.input().rcpt_dt(),w.input().rcpt_amt(),w.input().rmrk_desc()));return 1;});
        when(mapper.delete(any())).thenAnswer(i->{var k=i.getArgument(0,ReceiptKey.class);return rows.remove(k.receiptId())==null?0:1;});
        doAnswer(i->{audits.add(i.getArgument(0));return null;}).when(mapper).audit(any());
        when(mapper.billing(anyLong())).thenAnswer(i->{long sum=rows.values().stream().mapToLong(ReceiptRow::rcpt_amt).sum();return json.writeValueAsString(Map.of("bill_id",1,"sply_amt",100,"atrb_ym","2026-09","tot_amt",110,"adjusted_total_amt",110,"received_amt",sum,"receivable_amt",110-sum));});
    }
    ResultActions save(String method,String body) throws Exception {
        return mvc.perform(request(org.springframework.http.HttpMethod.valueOf(method),method.equals("POST")?BASE:BASE+"/1")
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }
    String input(long amount) {return "{\"rcpt_dt\":\"2026-10-23\",\"rcpt_amt\":"+amount+",\"rmrk_desc\":\"입금\"}";}
    void existing() {rows.put(1L,new ReceiptRow(1,LocalDate.of(2026,10,22),30,"이전"));sequence=1;}
    void unchanged() {verify(mapper,never()).insert(any());verify(mapper,never()).update(any());verify(mapper,never()).delete(any());assertThat(audits).isEmpty();}
    @Test void anonymousListReturnsAmountsAndDoesNotWrite() throws Exception {
        existing();mvc.perform(get(BASE)).andExpect(status().isOk()).andExpect(jsonPath("$.data.receipts[0].receipt_id").value(1))
            .andExpect(jsonPath("$.data.billing.receivable_amt").value(80)).andExpect(jsonPath("$.data.details").isArray());unchanged();
    }
    @Test void anonymousCreatePreservesSupplyAndMonthAndAuditsUnderLock() throws Exception {
        save("POST",input(30)).andExpect(status().isOk()).andExpect(jsonPath("$.data.billing.receivable_amt").value(80))
            .andExpect(jsonPath("$.data.billing.sply_amt").value(100)).andExpect(jsonPath("$.data.billing.atrb_ym").value("2026-09"))
            .andExpect(header().exists("X-Request-Id"));
        assertThat(audits).hasSize(1);assertThat(audits.get(0).actor()).isNull();assertThat(audits.get(0).before()).isNull();
        assertThat(audits.get(0).after()).contains("receipt_id","rcpt_dt","rcpt_amt","rmrk_desc");
        var order=inOrder(mapper);order.verify(mapper).lockBill(1);order.verify(mapper).receivedExcluding(any());order.verify(mapper).insert(any());order.verify(mapper).audit(any());
    }
    @Test void anonymousUpdateReplacesSelectedReceiptAndRecordsBothSnapshots() throws Exception {
        existing();save("PUT",input(40)).andExpect(status().isOk()).andExpect(jsonPath("$.data.billing.receivable_amt").value(70));
        assertThat(audits.get(0).before()).contains("2026-10-22","30","이전");assertThat(audits.get(0).after()).contains("2026-10-23","40","입금");
        assertThat(bill.tot_amt()).isEqualTo(110);
        verify(mapper).receivedExcluding(new ReceiptKey(1,1));
    }
    @Test void anonymousDeleteIncreasesBalanceAndKeepsDeletedValuesInAudit() throws Exception {
        existing();rows.put(2L,new ReceiptRow(2,LocalDate.of(2026,10,23),20,null));
        mvc.perform(delete(BASE+"/1")).andExpect(status().isOk()).andExpect(jsonPath("$.data.billing.received_amt").value(20))
            .andExpect(jsonPath("$.data.billing.receivable_amt").value(90));
        assertThat(rows).containsOnlyKeys(2L);assertThat(audits.get(0).type()).isEqualTo("DELETE");
        assertThat(audits.get(0).before()).contains("이전","30");assertThat(audits.get(0).after()).isNull();
    }
    @ParameterizedTest @CsvSource({"POST,0","POST,-1","PUT,0","PUT,-1"})
    void nonpositiveAmountHasNoWrites(String method,long amount) throws Exception {
        existing();save(method,input(amount)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.field").value("rcpt_amt"));unchanged();assertThat(rows.get(1L).rcpt_amt()).isEqualTo(30);
    }
    @ParameterizedTest @ValueSource(strings={"POST","PUT"})
    void missingDateHasNoWrites(String method) throws Exception {
        existing();save(method,"{\"rcpt_amt\":30}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("rcpt_dt"));unchanged();
    }
    @ParameterizedTest @ValueSource(strings={"POST","PUT"})
    void missingAmountHasNoWrites(String method) throws Exception {
        save(method,"{\"rcpt_dt\":\"2026-10-23\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("rcpt_amt"));unchanged();
    }
    @ParameterizedTest @ValueSource(strings={"1.5","\"30\"","9223372036854775808","null"})
    void strictIntegerWireAmount(String amount) throws Exception {
        save("POST","{\"rcpt_dt\":\"2026-10-23\",\"rcpt_amt\":"+amount+"}").andExpect(status().isBadRequest());unchanged();
    }
    @ParameterizedTest @ValueSource(strings={"POST","PUT"})
    void overflowUsesAdjustedTotal(String method) throws Exception {
        if(method.equals("PUT")) existing();when(mapper.amendmentTotal(1)).thenReturn(-22L);
        save(method,input(89)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OVER_RECEIPT"));unchanged();
    }
    @ParameterizedTest @ValueSource(strings={"PLAN","DRAFT","CANCEL"})
    void onlyConfirmedParentsAcceptReceipts(String state) throws Exception {
        bill=new BillState(1,state,"N",null,110);save("POST",input(30)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_BILL"));unchanged();
    }
    @Test void amendmentCannotReceive() throws Exception {
        bill=new BillState(1,"CONFIRM","Y",2L,110);save("POST",input(30)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_BILL"));unchanged();
    }
    @ParameterizedTest @ValueSource(strings={"POST","PUT","DELETE"})
    void closedReceiptMonthBlocksMutation(String method) throws Exception {
        existing();when(mapper.monthStatus("2026-10")).thenReturn("CLOSED");
        var result=method.equals("DELETE")?mvc.perform(delete(BASE+"/1")):save(method,input(20));
        result.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH"));unchanged();assertThat(rows.get(1L).rcpt_amt()).isEqualTo(30);
    }
    @Test void oldClosedMonthCannotBeEscapedByDateMove() throws Exception {
        rows.put(1L,new ReceiptRow(1,LocalDate.of(2026,9,15),30,null));when(mapper.monthStatus("2026-09")).thenReturn("CLOSED");
        save("PUT",input(20)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH"));unchanged();
    }
    @Test void newClosedMonthAlsoBlocksMove() throws Exception {
        existing();when(mapper.monthStatus("2026-11")).thenReturn("CLOSED");
        save("PUT",input(20).replace("2026-10-23","2026-11-02")).andExpect(status().isConflict());unchanged();
    }
    @Test void billMonthClosureDoesNotBlockOpenReceiptMonth() throws Exception {
        when(mapper.monthStatus("2026-09")).thenReturn("CLOSED");save("POST",input(30)).andExpect(status().isOk());
        verify(mapper,never()).monthStatus("2026-09");
    }
    @ParameterizedTest @ValueSource(strings={"POST","PUT"})
    void pastReceiptYearIsIndependentValidation(String method) throws Exception {
        existing();save(method,input(20).replace("2026-10-23","2025-12-31")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));unchanged();
    }
    @ParameterizedTest @ValueSource(strings={"PUT","DELETE"})
    void receiptOutsidePathIsNotMutated(String method) throws Exception {
        existing();when(mapper.receipt(new ReceiptKey(1,1))).thenReturn(null);
        var result=method.equals("DELETE")?mvc.perform(delete(BASE+"/1")):save(method,input(20));
        result.andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("receiptId"));unchanged();assertThat(rows).hasSize(1);
    }
    @ParameterizedTest @ValueSource(strings={"GET","POST","PUT","DELETE"})
    void missingParentUsesContract400(String method) throws Exception {
        when(mapper.bill(1)).thenReturn(null);when(mapper.lockBill(1)).thenReturn(null);
        var result=method.equals("GET")?mvc.perform(get(BASE)):method.equals("DELETE")?mvc.perform(delete(BASE+"/1")):save(method,input(20));
        result.andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("id"));unchanged();
    }
    @Test void staleAffectedRowIsNotAudited() throws Exception {
        existing();doReturn(0).when(mapper).update(any());save("PUT",input(20)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_BILL"));
        assertThat(audits).isEmpty();
    }
    @ParameterizedTest @CsvSource({"PUT,DRAFT","PUT,CANCEL","PUT,PLAN","DELETE,DRAFT","DELETE,CANCEL","DELETE,PLAN"})
    void editsAndRemovalRequireConfirmedParent(String method,String state) throws Exception {
        existing();bill=new BillState(1,state,"N",null,110);
        var result=method.equals("DELETE")?mvc.perform(delete(BASE+"/1")):save(method,input(20));
        result.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_BILL"));unchanged();
        assertThat(rows.get(1L).rcpt_amt()).isEqualTo(30);
    }
    @ParameterizedTest @ValueSource(strings={"PUT","DELETE"})
    void amendmentsCannotBeEditedOrRemovedThroughReceiptApi(String method) throws Exception {
        existing();bill=new BillState(1,"CONFIRM","Y",2L,110);
        var result=method.equals("DELETE")?mvc.perform(delete(BASE+"/1")):save(method,input(20));
        result.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_BILL"));unchanged();
    }
    @ParameterizedTest @ValueSource(strings={"2026-02-30","not-a-date","2026-1-2"})
    void malformedDateIsFieldValidation(String date) throws Exception {
        save("POST",input(20).replace("2026-10-23",date)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR")).andExpect(jsonPath("$.field").value("rcpt_dt"));unchanged();
    }
    @Test void updateIncludesOtherReceiptsButExcludesReplacedAmount() throws Exception {
        existing();rows.put(2L,new ReceiptRow(2,LocalDate.of(2026,10,23),60,null));
        save("PUT",input(50)).andExpect(status().isOk()).andExpect(jsonPath("$.data.billing.receivable_amt").value(0));
        assertThat(rows.get(2L).rcpt_amt()).isEqualTo(60);
    }
    @Test void deleteOfPastReceiptYearIsRejectedWithoutMutation() throws Exception {
        rows.put(1L,new ReceiptRow(1,LocalDate.of(2025,12,31),30,null));
        mvc.perform(delete(BASE+"/1")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));unchanged();
    }
}
