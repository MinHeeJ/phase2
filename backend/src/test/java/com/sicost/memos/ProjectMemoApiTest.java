package com.sicost.memos;

import static com.sicost.memos.ProjectMemoModels.*;
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
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.test.web.servlet.*;

@WebMvcTest(controllers = ProjectMemoController.class)
@Import({RequestIdFilter.class, ApiExceptionHandler.class, ProjectMemoService.class,
    CurrentUser.class, PermissionService.class, ProjectMemoApiTest.Time.class})
class ProjectMemoApiTest {
    @TestConfiguration static class Time {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-10-22T15:01:00Z"), ZoneId.of("UTC")); }
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean ProjectMemoMapper mapper;
    final Map<Long, MemoRow> rows = new LinkedHashMap<>();
    ProjectState project;
    long sequence;
    static final LocalDate TODAY = LocalDate.of(2026,10,23);
    static final String BASE = "/api/projects/1/memos";
    @BeforeEach void setup() {
        project = new ProjectState(1,"TK","Y","RUN"); rows.clear(); sequence = 0;
        when(mapper.lockProject(anyLong())).thenAnswer(i -> i.getArgument(0, Long.class) == 1 ? project : null);
        when(mapper.today(any())).thenAnswer(i -> {
            var key = i.getArgument(0, MemoDate.class);
            return rows.values().stream().filter(r -> r.prj_id() == key.id() && r.wrt_dt().equals(key.today())).findFirst().orElse(null);
        });
        when(mapper.latest(anyLong())).thenAnswer(i -> rows.values().stream()
            .filter(r -> r.prj_id() == i.getArgument(0, Long.class))
            .max(Comparator.comparing(MemoRow::wrt_dt).thenComparingLong(MemoRow::memo_id)).orElse(null));
        when(mapper.row(any())).thenAnswer(i -> {
            var key = i.getArgument(0, MemoKey.class); var row = rows.get(key.memoId());
            return row != null && row.prj_id() == key.id() ? row : null;
        });
        when(mapper.insert(any())).thenAnswer(i -> {
            var w = i.getArgument(0, MemoWrite.class); long id = ++sequence; var r = w.row();
            rows.put(id, new MemoRow(id,w.id(),r.wrt_dt(),r.actl_prgs_rt(),r.cmpl_work_desc(),r.plan_work_desc(),r.rqst_desc()));
            return id;
        });
        doAnswer(i -> { var w = i.getArgument(0,MemoWrite.class); rows.put(w.row().memo_id(),w.row()); return null; }).when(mapper).update(any());
    }
    Map<String,Object> input() {
        var body = new LinkedHashMap<String,Object>(); body.put("actl_prgs_rt",42);
        body.put("cmpl_work_desc","개발 완료"); body.put("plan_work_desc","통합 시험"); body.put("rqst_desc",null); return body;
    }
    ResultActions send(String method, String path, Map<String,Object> body) throws Exception {
        return mvc.perform(request(HttpMethod.valueOf(method),path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }
    void noWrites() { verify(mapper, never()).insert(any()); verify(mapper, never()).update(any()); }
    void oldRow() { rows.put(7L,new MemoRow(7,1,TODAY.minusDays(7),new BigDecimal("30.00"),"이전 한 일","이전 할 일",null)); }
    @Test void memoOperationsComeFromDurableClasspathContract() throws Exception {
        try (var in = new org.springframework.core.io.ClassPathResource("contracts/openapi.yaml").getInputStream()) {
            Map<?, ?> contract = new org.yaml.snakeyaml.Yaml().load(in);
            Map<?, ?> paths = (Map<?, ?>) contract.get("paths");
            Map<?, ?> create = (Map<?, ?>) ((Map<?, ?>) paths.get("/api/projects/{id}/memos")).get("post");
            Map<?, ?> update = (Map<?, ?>) ((Map<?, ?>) paths.get("/api/projects/{id}/memos/{memoId}")).get("put");
            assertThat(create.get("operationId")).isEqualTo("saveProjectMemo");
            assertThat(update.get("operationId")).isEqualTo("updateProjectMemo");
            assertThat(create.get("responses")).isInstanceOf(Map.class);
            assertThat(update.get("responses")).isInstanceOf(Map.class);
        }
    }
    @Test void postValidationRejectsFractionalProgressWithoutInsert() throws Exception {
        var body = input(); body.put("actl_prgs_rt", new BigDecimal("42.5"));
        mvc.perform(post("/api/projects/1/memos").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body))).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.field").value("actl_prgs_rt"))
            .andExpect(jsonPath("$.message").isNotEmpty()).andExpect(jsonPath("$.requestId").isNotEmpty());
        assertThat(rows).isEmpty(); noWrites();
    }
    @Test void putValidationPreservesMemoIdAndWrtDt() throws Exception {
        oldRow(); var before = rows.get(7L); var body = input(); body.put("plan_work_desc", " ");
        mvc.perform(put("/api/projects/1/memos/7").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body))).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.field").value("plan_work_desc"))
            .andExpect(jsonPath("$.message").value("한 일과 할 일을 적어 주세요."));
        assertThat(rows.get(7L)).isEqualTo(before); noWrites();
    }
    @Test void postBusinessClosedProjectDoesNotInsert() throws Exception {
        project = new ProjectState(1, "TK", "Y", "END");
        mvc.perform(post("/api/projects/1/memos").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(input()))).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PROJECT_CLOSED"));
        assertThat(rows).isEmpty(); noWrites();
    }
    @Test void putBusinessNonLatestMemoPreservesBothRows() throws Exception {
        oldRow(); rows.put(8L, new MemoRow(8,1,TODAY,BigDecimal.TEN,"오늘","계획",null));
        var before = new LinkedHashMap<>(rows);
        mvc.perform(put("/api/projects/1/memos/7").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(input()))).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("NOT_LATEST_MEMO"));
        assertThat(rows).isEqualTo(before); noWrites();
    }
    @Test void putNotFoundDoesNotWriteAnotherMemo() throws Exception {
        mvc.perform(put("/api/projects/1/memos/999").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(input()))).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        noWrites();
    }
    @Test void anonymousTodayInsertSideEffectUsesSeoulDateNullActorAndExactEnvelope() throws Exception {
        mvc.perform(post("/api/projects/1/memos").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(input()))).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.meta").isMap()).andExpect(jsonPath("$.data.memo_id").value(1))
            .andExpect(jsonPath("$.data.wrt_dt").value("2026-10-23"))
            .andExpect(jsonPath("$.data.actl_prgs_rt").value(42));
        assertThat(rows).hasSize(1);
        verify(mapper).insert(argThat(w -> w.actor() == null && w.row().wrt_dt().equals(TODAY)));
        var order = inOrder(mapper); order.verify(mapper).lockProject(1); order.verify(mapper).today(any()); order.verify(mapper).insert(any());
    }
    @Test void sameDayPostKeepsIdentityAndLastContentAndAllowsDecrease() throws Exception {
        send("POST",BASE,input()).andExpect(status().isOk());
        var body = input(); body.put("actl_prgs_rt",0); body.put("cmpl_work_desc","두번째 내용");
        send("POST",BASE,body).andExpect(status().isOk()).andExpect(jsonPath("$.data.memo_id").value(1))
            .andExpect(jsonPath("$.data.cmpl_work_desc").value("두번째 내용")).andExpect(jsonPath("$.data.actl_prgs_rt").value(0));
        assertThat(rows).hasSize(1); verify(mapper,times(1)).insert(any());
    }
    @Test void anonymousLatestPutSideEffectPreservesOldDateAndIdentity() throws Exception {
        oldRow();
        var body = input(); body.put("plan_work_desc","바꾼 할 일");
        mvc.perform(put("/api/projects/1/memos/7").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body))).andExpect(status().isOk()).andExpect(jsonPath("$.data.memo_id").value(7))
            .andExpect(jsonPath("$.data.wrt_dt").value("2026-10-16"))
            .andExpect(jsonPath("$.data.plan_work_desc").value("바꾼 할 일"));
        assertThat(rows).hasSize(1); verify(mapper,never()).insert(any());
        var order = inOrder(mapper); order.verify(mapper).lockProject(1); order.verify(mapper).row(any());
        order.verify(mapper).latest(1); order.verify(mapper).update(any());
    }
    @ParameterizedTest @CsvSource({"TK,0","TK,100","IN,0","IN,100"})
    void exactIntegerBoundaries(String type,int rate) throws Exception {
        project = new ProjectState(1,type,"Y","HOLD"); var body = input(); body.put("actl_prgs_rt",rate);
        send("POST",BASE,body).andExpect(status().isOk()).andExpect(jsonPath("$.data.actl_prgs_rt").value(rate));
    }
    @ParameterizedTest @ValueSource(strings={"MO","DP"})
    void operatingTypesIgnoreEvenFractionalOrNonNumericRates(String type) throws Exception {
        project = new ProjectState(1,type,"Y","PLAN"); var body = input(); body.put("actl_prgs_rt",new BigDecimal("42.5"));
        send("POST",BASE,body).andExpect(status().isOk()).andExpect(jsonPath("$.data.actl_prgs_rt").isEmpty());
        body.put("actl_prgs_rt","ignored");
        send("PUT",BASE+"/1",body).andExpect(status().isOk()).andExpect(jsonPath("$.data.actl_prgs_rt").isEmpty());
        assertThat(rows.get(1L).actl_prgs_rt()).isNull();
    }
    @ParameterizedTest @CsvSource({"POST,105","POST,42.5","POST,-1","PUT,105","PUT,42.5","PUT,-1"})
    void invalidRateNeverChangesRows(String method,String rate) throws Exception {
        oldRow(); var before = new LinkedHashMap<>(rows); var body = input(); body.put("actl_prgs_rt",new BigDecimal(rate));
        send(method,method.equals("POST")?BASE:BASE+"/7",body).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.field").value("actl_prgs_rt")).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        assertThat(rows).isEqualTo(before); noWrites();
    }
    @ParameterizedTest @ValueSource(strings={"POST","PUT"})
    void missingRateRejectedForTk(String method) throws Exception {
        oldRow(); var body = input(); body.remove("actl_prgs_rt");
        send(method,method.equals("POST")?BASE:BASE+"/7",body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("actl_prgs_rt")); noWrites();
    }
    @ParameterizedTest @CsvSource({"POST,cmpl_work_desc","POST,plan_work_desc","PUT,cmpl_work_desc","PUT,plan_work_desc"})
    void blankRequiredTextHasExactMessageAndNoWrites(String method,String field) throws Exception {
        oldRow(); var before = new LinkedHashMap<>(rows); var body = input(); body.put(field,"  ");
        send(method,method.equals("POST")?BASE:BASE+"/7",body).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.field").value(field)).andExpect(jsonPath("$.message").value("한 일과 할 일을 적어 주세요."))
            .andExpect(jsonPath("$.requestId").isNotEmpty());
        assertThat(rows).isEqualTo(before); noWrites();
    }
    @ParameterizedTest @CsvSource({"POST,cmpl_work_desc","POST,plan_work_desc","POST,rqst_desc","PUT,cmpl_work_desc","PUT,plan_work_desc","PUT,rqst_desc"})
    void textLengthLimit(String method,String field) throws Exception {
        oldRow(); var body = input(); body.put(field,"가".repeat(201));
        send(method,method.equals("POST")?BASE:BASE+"/7",body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field)); noWrites();
    }
    @ParameterizedTest @ValueSource(strings={"POST","PUT"})
    void twoHundredCharactersAccepted(String method) throws Exception {
        oldRow(); var body = input(); for (var key : List.of("cmpl_work_desc","plan_work_desc","rqst_desc")) body.put(key,"가".repeat(200));
        send(method,method.equals("POST")?BASE:BASE+"/7",body).andExpect(status().isOk());
    }
    @ParameterizedTest @CsvSource({"POST,END,Y","POST,LOST,Y","POST,RUN,N","PUT,END,Y","PUT,LOST,Y","PUT,HOLD,N"})
    void closedProjectsConflictWithoutChangingMemo(String method,String state,String use) throws Exception {
        oldRow(); var before = new LinkedHashMap<>(rows); project = new ProjectState(1,"TK",use,state);
        send(method,method.equals("POST")?BASE:BASE+"/7",input()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PROJECT_CLOSED"));
        assertThat(rows).isEqualTo(before); noWrites();
    }
    @Test void olderMemoIsConflictNotDeleteOrOverwrite() throws Exception {
        oldRow(); rows.put(8L,new MemoRow(8,1,TODAY,BigDecimal.TEN,"오늘 한 일","오늘 할 일",null));
        var before = new LinkedHashMap<>(rows);
        send("PUT",BASE+"/7",input()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_LATEST_MEMO"));
        assertThat(rows).isEqualTo(before); noWrites();
    }
    @ParameterizedTest @ValueSource(strings={"POST","PUT"})
    void missingProject404(String method) throws Exception {
        send(method,"/api/projects/2/memos"+(method.equals("PUT")?"/7":""),input()).andExpect(status().isNotFound()); noWrites();
    }
    @Test void missingOrWrongProjectMemoIs404EvenIfClosed() throws Exception {
        project = new ProjectState(1,"TK","Y","END");
        rows.put(7L,new MemoRow(7,2,TODAY,BigDecimal.TEN,"타 프로젝트","할 일",null));
        send("PUT",BASE+"/7",input()).andExpect(status().isNotFound()); noWrites();
    }
    @ParameterizedTest @ValueSource(strings={"memo_id","prj_id","wrt_dt","wrt_user_id"})
    void bodyCannotSelectIdentityOrAuthor(String field) throws Exception {
        var body = input(); body.put(field,1);
        send("POST",BASE,body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field)); noWrites();
    }
    @ParameterizedTest @ValueSource(strings={"null","[]","{bad}","{} {}"})
    void malformedBody400(String body) throws Exception {
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest()); noWrites();
    }
    @ParameterizedTest @ValueSource(strings={"/api/projects/0/memos","/api/projects/1/memos/0"})
    void invalidIdentifiers400(String path) throws Exception {
        send(path.endsWith("/0")?"PUT":"POST",path,input()).andExpect(status().isBadRequest()); noWrites();
    }
}
