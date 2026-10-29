package com.sicost.assignments;

import static com.sicost.assignments.AssignmentModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.persistence.AssignmentConcurrencyService;
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
import org.springframework.test.web.servlet.*;

@WebMvcTest(controllers = AssignmentController.class)
@Import({RequestIdFilter.class, ApiExceptionHandler.class, AssignmentService.class,
    CurrentUser.class, PermissionService.class, AssignmentApiTest.Time.class})
class AssignmentApiTest {
    @TestConfiguration static class Time {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-10-13T15:00:00Z"), ZoneId.of("Asia/Seoul")); }
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean AssignmentMapper mapper;
    @MockBean AssignmentConcurrencyService concurrency;
    Row row;
    @BeforeEach void setup() {
        row = row("ACTIVE", false, false, false, false, 7000000L);
        when(mapper.project(1)).thenReturn(new Project(1, "MO", "RUN", "Y"));
        when(mapper.developer(anyLong())).thenReturn(new Developer(1,"Y",null));
        when(mapper.activeCode(any())).thenReturn(true);
        when(mapper.closedMonths()).thenReturn(List.of("2026-09"));
        when(mapper.nextId()).thenReturn(1L); when(mapper.start(any())).thenReturn(1L);
        when(mapper.row(any())).thenAnswer(i -> i.getArgument(0, ReadKey.class).id() == 1 ? row : null);
        when(mapper.list(any())).thenAnswer(i -> List.of(row));
        when(mapper.rates(1)).thenReturn(List.of(new Rate(LocalDate.of(2026,10,16),7000000L)));
        when(mapper.months(1)).thenReturn(List.of(new AssignmentModels.Month("2026-10",new BigDecimal("0.52"),new BigDecimal("0.52"),"N",new BigDecimal("0.52"),"N")));
    }
    Row row(String status, boolean over, boolean plan, boolean leave, boolean dispatch, Long price) {
        return new Row(1,"A-0001",1,"프로젝트",1,"개발자","K-0001","BE",LocalDate.of(2026,10,16),LocalDate.of(2026,12,31),
            LocalDate.of(2026,11,30),BigDecimal.ONE,"NEW",price == null ? null : "MONTH",null,null,null,status,null,price,
            new BigDecimal("0.52"),BigDecimal.ONE,new BigDecimal("2.52"),LocalDateTime.parse("2026-10-14T00:00:00.123456"),null,1,over,plan,leave,dispatch);
    }
    Map<String,Object> input() {
        var m = new LinkedHashMap<String,Object>();
        m.put("prj_id",1); m.put("dev_id",1); m.put("job_cd","BE"); m.put("bgn_dt","2026-10-16"); m.put("end_dt","2026-10-31");
        m.put("asgn_rt",1); m.put("asgn_se_cd","NEW"); m.put("bill_untprc_amt",7000000); m.put("untprc_base_cd","MONTH"); return m;
    }
    ResultActions save(Map<String,Object> input) throws Exception {
        return mvc.perform(post("/api/assignments").contentType("application/json").content(json.writeValueAsString(input)));
    }
    @Test void anonymousListReadsConcreteDataWithoutWriting() throws Exception {
        mvc.perform(get("/api/assignments").param("year","2026").param("projectId","1").param("job","BE"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.items[0].year_mm_qty").value(2.52)).andExpect(jsonPath("$.data.total").value(1));
        verify(mapper).list(argThat(q -> q.projectId() == 1 && q.job().equals("BE")));
        verify(mapper,never()).insert(any()); verify(mapper,never()).month(any()); verify(mapper,never()).rate(any()); verify(mapper,never()).start(any());
    }
    @ParameterizedTest @ValueSource(strings={"warning=UNKNOWN","sort=sql,asc","page=-1","size=10","year=zero"})
    void invalidQueriesReturnField(String query) throws Exception {
        String[] p=query.split("=");
        mvc.perform(get("/api/assignments").param("year",p[0].equals("year")?p[1]:"2026").param(p[0],p[1]))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(p[0]));
    }
    @Test void emptyListReturnsZeroTotal() throws Exception {
        when(mapper.list(any())).thenReturn(List.of());
        mvc.perform(get("/api/assignments").param("year","2026").param("q","없는 이름"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty()).andExpect(jsonPath("$.data.total").value(0));
    }
    @Test void warningFilterUsesFreshCausesAndSameTotal() throws Exception {
        row=row("ACTIVE",true,true,true,true,7000000L);
        mvc.perform(get("/api/assignments").param("year","2026").param("warning","OVER_MM"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].warnings.length()").value(4)).andExpect(jsonPath("$.data.total").value(1));
        row=row("ACTIVE",false,false,false,false,7000000L);
        mvc.perform(get("/api/assignments").param("year","2026").param("warning","OVER_MM"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
    }
    @Test void anonymousDetailPreservesPlanRatesAndTimestampPrecision() throws Exception {
        mvc.perform(get("/api/assignments/1")).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.asgn_no").value("A-0001")).andExpect(jsonPath("$.data.extend_count").value(1))
            .andExpect(jsonPath("$.data.plan_end_dt").value("2026-11-30"))
            .andExpect(jsonPath("$.data.updated_at").value("2026-10-14T00:00:00.123456"))
            .andExpect(jsonPath("$.data.rates[0].bill_untprc_amt").value(7000000));
    }
    @Test void missingDetailIs404() throws Exception { mvc.perform(get("/api/assignments/9")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND")); }
    @Test void cancelledTkDetailIsReadableWithoutSensitivePricesOrWrites() throws Exception {
        row=row("CANCELLED",false,false,false,false,null);
        mvc.perform(get("/api/assignments/1")).andExpect(status().isOk()).andExpect(jsonPath("$.data.asgn_stts_cd").value("CANCELLED"))
            .andExpect(jsonPath("$.data.bill_untprc_amt").isEmpty()).andExpect(jsonPath("$.data.untprc_base_cd").isEmpty())
            .andExpect(jsonPath("$.data.pay_untprc_amt").doesNotExist()).andExpect(jsonPath("$.data.ovtm_untprc_amt").doesNotExist());
        verify(mapper,never()).insert(any()); verify(mapper,never()).start(any());
    }
    @Test void anonymousCreateWritesParentRateOpenMmAndStart() throws Exception {
        mvc.perform(post("/api/assignments").contentType("application/json").content(json.writeValueAsBytes(input())))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.assignment.asgn_no").value("A-0001"))
            .andExpect(jsonPath("$.data.assignment.asgn_stts_cd").value("ACTIVE"));
        var order=inOrder(concurrency,mapper); order.verify(concurrency).lockProject(1); order.verify(mapper).project(1);
        verify(mapper).insert(argThat(w -> w.id()==1 && w.number().equals("A-0001")));
        verify(mapper).month(argThat(w -> w.month().equals("2026-10") && w.quantity().compareTo(new BigDecimal("0.52"))==0));
        verify(mapper).rate(argThat(w -> w.amount()==7000000L));
        verify(mapper).start(argThat(w -> w.input().bgn_dt().equals(LocalDate.of(2026,10,16)) && w.retro().equals("N")));
    }
    @ParameterizedTest @ValueSource(strings={"0","1.01"}) void invalidRateRejected(String rate) throws Exception {
        var p=input(); p.put("asgn_rt",new BigDecimal(rate)); save(p).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("asgn_rt")); verify(mapper,never()).insert(any());
    }
    @Test void substituteRequiresContractPerson() throws Exception { var p=input(); p.put("asgn_se_cd","SUB"); save(p).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("cntrct_dev_id")); }
    @Test void oneDayProducesPointZeroThree() throws Exception { var p=input(); p.put("end_dt","2026-10-16"); save(p).andExpect(status().isOk()); verify(mapper).month(argThat(w -> w.quantity().compareTo(new BigDecimal("0.03"))==0)); }
    @Test void sameProjectOverlapIs409WithoutWrites() throws Exception { when(mapper.overlap(any())).thenReturn("A-0009 2026-10-01 ~ 2026-12-31"); save(input()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OVERLAP_ASSIGNMENT")); verify(mapper,never()).insert(any()); }
    @ParameterizedTest @ValueSource(strings={"END","LOST","DISABLED"}) void closedProjectIs409WithoutWrites(String status) throws Exception {
        when(mapper.project(1)).thenReturn(new Project(1,"MO",status.equals("DISABLED")?"RUN":status,status.equals("DISABLED")?"N":"Y"));
        save(input()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PROJECT_CLOSED")); verify(mapper,never()).insert(any());
    }
    @Test void allFourWarningsDoNotBlockCreate() throws Exception { row=row("ACTIVE",true,true,true,true,7000000L); save(input()).andExpect(status().isOk()).andExpect(jsonPath("$.data.assignment.warnings.length()").value(4)); }
    @Test void pastStartRequiresActiveReasonWithoutWrites() throws Exception { var p=input(); p.put("bgn_dt","2026-09-01"); save(p).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("rsn_cd")); verify(mapper,never()).insert(any()); }
    @Test void closedStartDefersRateAndSkipsMmButKeepsRequestedHistoryDate() throws Exception {
        var p=input(); p.put("bgn_dt","2026-09-01"); p.put("rsn_cd","SCHED"); save(p).andExpect(status().isOk());
        verify(mapper).rate(argThat(w -> w.date().equals(LocalDate.of(2026,10,1))));
        verify(mapper,never()).month(argThat(w -> w.month().equals("2026-09")));
        verify(mapper).start(argThat(w -> w.input().bgn_dt().equals(LocalDate.of(2026,9,1)) && w.retro().equals("Y")));
    }
    @Test void entirelyClosedAssignmentHasHistoryOnlyFinancially() throws Exception {
        var p=input(); p.put("bgn_dt","2026-09-01"); p.put("end_dt","2026-09-30"); p.put("rsn_cd","SCHED"); save(p).andExpect(status().isOk());
        verify(mapper,never()).rate(any()); verify(mapper,never()).month(any()); verify(mapper).start(any());
    }
    @ParameterizedTest @ValueSource(strings={"pay_untprc_amt","ovtm_untprc_amt","asgn_id"}) void unknownInputsRejected(String key) throws Exception { var p=input(); p.put(key,1); save(p).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(key)); }
}
