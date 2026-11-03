package com.sicost.tempassignments;

import static com.sicost.tempassignments.TempAssignmentModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.security.CurrentUser;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.*;

@WebMvcTest(controllers=TempAssignmentController.class)
@Import({RequestIdFilter.class,ApiExceptionHandler.class,TempAssignmentService.class,CurrentUser.class})
class TempAssignmentApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean TempAssignmentMapper mapper;
    Map<Long,Row> rows;
    List<History> history;
    Map<String,Object> body() {
        return new LinkedHashMap<>(Map.of("need_id",11,"dev_id",21,"bgn_ym","2026-11","end_ym","2027-01",
            "mth_mm_qty",new BigDecimal("0.40"),"tmpr_asgn_src_cd","MANUAL"));
    }
    @BeforeEach void setup() {
        rows=new LinkedHashMap<>(); history=new ArrayList<>();
        when(mapper.opportunityId(anyLong())).thenReturn(31L);
        when(mapper.lockOpportunity(31)).thenReturn("OPEN");
        when(mapper.lockNeed(anyLong())).thenAnswer(i -> new Need(i.getArgument(0),31,"Y"));
        when(mapper.lockDeveloper(anyLong())).thenAnswer(i -> i.getArgument(0));
        when(mapper.recommendationScore(any())).thenReturn(new BigDecimal("90.00"));
        when(mapper.insert(any())).thenAnswer(i -> {
            var w=i.getArgument(0,Insert.class); var b=w.input(); long id=rows.size()+1L;
            rows.put(id,new Row(id,b.need_id(),b.dev_id(),b.bgn_ym(),b.end_ym(),b.mth_mm_qty(),b.tmpr_asgn_src_cd(),w.score(),"ACTIVE",null)); return id;
        });
        when(mapper.row(anyLong())).thenAnswer(i -> rows.get(i.getArgument(0,Long.class)));
        when(mapper.release(any())).thenAnswer(i -> {
            var key=i.getArgument(0,Release.class); var r=rows.get(key.id());
            if(r==null || !r.status().equals("ACTIVE")) return 0;
            rows.put(r.id(),new Row(r.id(),r.need_id(),r.dev_id(),r.bgn_ym(),r.end_ym(),r.mth_mm_qty(),r.tmpr_asgn_src_cd(),r.mtch_scr(),"RELEASED",r.asgn_id())); return 1;
        });
        when(mapper.list(any())).thenAnswer(i -> filtered(i.getArgument(0)));
        when(mapper.count(any())).thenAnswer(i -> (long)filtered(i.getArgument(0)).size());
        when(mapper.warnings(any())).thenReturn(Collections.emptyList());
        doAnswer(i -> {history.add(i.getArgument(0));return null;}).when(mapper).appendHistory(any());
    }
    List<Row> filtered(Query q) {
        return rows.values().stream().filter(r -> q.status()==null || q.status().equals(r.status()))
            .filter(r -> q.need_id()==null || q.need_id()==r.need_id())
            .filter(r -> q.dev_id()==null || q.dev_id()==r.dev_id()).toList();
    }
    ResultActions create(Map<String,Object> input) throws Exception {
        return mvc.perform(post("/api/temp-assignments").contentType("application/json").content(json.writeValueAsString(input)));
    }
    void bad(ResultActions result,String field) throws Exception {
        result.andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field))
            .andExpect(jsonPath("$.message").isNotEmpty()).andExpect(jsonPath("$.requestId").isNotEmpty());
    }
    @Test void anonymousCreateAndGetRoundTripManualAndAudit() throws Exception {
        create(body()).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.status").value("ACTIVE")).andExpect(jsonPath("$.data.mtch_scr").isEmpty());
        mvc.perform(get("/api/temp-assignments").param("need_id","11").param("status","ACTIVE"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].mth_mm_qty").value(0.4))
            .andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.page").value(0));
        assertThat(history).hasSize(1);assertThat(history.get(0).actor()).isNull();
        assertThat(json.readTree(history.get(0).after()).get("status").textValue()).isEqualTo("ACTIVE");
    }
    @Test void nextScoreIsServerCalculatedIgnoringClientScore() throws Exception {
        var b=body();b.put("tmpr_asgn_src_cd","NEXT");b.put("mtch_scr",1);
        create(b).andExpect(status().isOk()).andExpect(jsonPath("$.data.mtch_scr").value(90));
        verify(mapper).recommendationScore(any());
    }
    @Test void manualDiscardsClientScore() throws Exception {
        var b=body();b.put("mtch_scr",100);create(b).andExpect(status().isOk()).andExpect(jsonPath("$.data.mtch_scr").isEmpty());
        verify(mapper,never()).recommendationScore(any());
    }
    @ParameterizedTest @ValueSource(strings={"0.01","1.00"}) void inclusiveMmBoundaries(String mm) throws Exception {
        var b=body();b.put("mth_mm_qty",new BigDecimal(mm));create(b).andExpect(status().isOk());
        assertThat(rows.get(1L).mth_mm_qty()).isEqualByComparingTo(mm);
    }
    @ParameterizedTest @ValueSource(strings={"0","1.01","-0.01","0.001"}) void rejectsInvalidMmWithoutWrites(String mm) throws Exception {
        var b=body();b.put("mth_mm_qty",new BigDecimal(mm));bad(create(b),"mth_mm_qty");assertThat(rows).isEmpty();assertThat(history).isEmpty();
    }
    @Test void rejectsReversedPeriod() throws Exception {var b=body();b.put("end_ym","2026-10");bad(create(b),"end_ym");assertThat(rows).isEmpty();}
    @ParameterizedTest @ValueSource(strings={"2026-13","2026-1","2026-00"}) void rejectsInvalidMonth(String month) throws Exception {
        var b=body();b.put("bgn_ym",month);bad(create(b),"bgn_ym");assertThat(rows).isEmpty();
    }
    @Test void rejectsUnknownBodyProperty() throws Exception {var b=body();b.put("status","CONVERTED");bad(create(b),"status");assertThat(rows).isEmpty();}
    @Test void rejectsInvalidDeveloper() throws Exception {when(mapper.lockDeveloper(21)).thenReturn(null);bad(create(body()),"dev_id");assertThat(rows).isEmpty();}
    @Test void rejectsMissingNeed() throws Exception {when(mapper.opportunityId(11)).thenReturn(null);bad(create(body()),"need_id");assertThat(rows).isEmpty();}
    @ParameterizedTest @ValueSource(strings={"WON","LOST","DELETED"}) void closedOpportunityCannotCreate(String state) throws Exception {
        when(mapper.lockOpportunity(31)).thenReturn(state);create(body()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OPPORTUNITY_CLOSED"));assertThat(rows).isEmpty();
    }
    @Test void inactiveNeedCannotCreate() throws Exception {when(mapper.lockNeed(11)).thenReturn(new Need(11,31,"N"));create(body()).andExpect(status().isConflict());assertThat(rows).isEmpty();}
    @Test void overlapConflictRechecksAfterParentAndNeedLocks() throws Exception {
        when(mapper.duplicate(any())).thenReturn(true);create(body()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_TEMP_ASSIGNMENT"));
        var order=inOrder(mapper);order.verify(mapper).opportunityId(11);order.verify(mapper).lockOpportunity(31);order.verify(mapper).lockNeed(11);
        order.verify(mapper).lockOpportunity(31);order.verify(mapper).lockDeveloper(21);order.verify(mapper).duplicate(any());assertThat(rows).isEmpty();
    }
    @Test void warningsDoNotBlockCreation() throws Exception {
        when(mapper.warnings(any())).thenReturn(List.of("2026-11 확정+가배정 MM 1.10 (1.00 초과)","필요 인원을 초과한 가배정입니다.","휴직 기간 가배정입니다.","퇴사 기간 가배정입니다.","다른 필요 인력과 가배정 기간이 겹칩니다."));
        create(body()).andExpect(status().isOk()).andExpect(jsonPath("$.data.warnings.length()").value(5));assertThat(rows).hasSize(1);
    }
    @Test void anonymousReleasePreservesOriginalAndAuditsBeforeAfter() throws Exception {
        create(body());mvc.perform(delete("/api/temp-assignments/1")).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.status").value("RELEASED"));
        mvc.perform(get("/api/temp-assignments").param("status","ACTIVE")).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/temp-assignments").param("status","RELEASED")).andExpect(jsonPath("$.data.items[0].id").value(1));
        assertThat(rows).hasSize(1);assertThat(history).hasSize(2);
        assertThat(json.readTree(history.get(1).before()).get("status").textValue()).isEqualTo("ACTIVE");
        assertThat(json.readTree(history.get(1).after()).get("status").textValue()).isEqualTo("RELEASED");
    }
    @Test void zeroReleaseIdLeavesOriginalUntouched() throws Exception {create(body());var before=rows.get(1L);bad(mvc.perform(delete("/api/temp-assignments/0")),"id");assertThat(rows.get(1L)).isEqualTo(before);assertThat(history).hasSize(1);}
    @Test void missingReleaseIdIs404() throws Exception {mvc.perform(delete("/api/temp-assignments/999")).andExpect(status().isNotFound());assertThat(history).isEmpty();}
    @Test void repeatedReleaseConflictsWithoutAdditionalAudit() throws Exception {create(body());mvc.perform(delete("/api/temp-assignments/1"));mvc.perform(delete("/api/temp-assignments/1")).andExpect(status().isConflict());assertThat(history).hasSize(2);}
    @Test void convertedReleaseCannotCancelConfirmedAssignment() throws Exception {
        rows.put(9L,new Row(9,11,21,"2026-11","2027-01",BigDecimal.ONE,"NEXT",BigDecimal.TEN,"CONVERTED",91L));var before=rows.get(9L);
        mvc.perform(delete("/api/temp-assignments/9")).andExpect(status().isConflict());assertThat(rows.get(9L)).isEqualTo(before);assertThat(history).isEmpty();
    }
    @ParameterizedTest @CsvSource({"from,2026-13","status,BOGUS","sort,unknown;asc","sort,id;sideways","page,-1","size,10","job,BE","need_id,0"})
    void invalidFiltersAre400AndReadOnly(String key,String value) throws Exception {bad(mvc.perform(get("/api/temp-assignments").param(key,value.replace(';',','))),key);verify(mapper,never()).insert(any());assertThat(history).isEmpty();}
    @Test void emptyAndRepeatedReadsAreStableWithoutBusinessWrites() throws Exception {
        mvc.perform(get("/api/temp-assignments")).andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty()).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/temp-assignments")).andExpect(status().isOk());verify(mapper,never()).insert(any());verify(mapper,never()).release(any());assertThat(history).isEmpty();
    }
}
