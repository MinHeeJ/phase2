package com.sicost.issues;

import static com.sicost.issues.IssueModels.*;
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

@WebMvcTest(controllers=IssueController.class)
@Import({RequestIdFilter.class,ApiExceptionHandler.class,IssueService.class,CurrentUser.class,
    PermissionService.class,IssueApiTest.Time.class})
class IssueApiTest {
    @TestConfiguration static class Time {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-10-22T15:01:00Z"),ZoneId.of("Asia/Seoul")); }
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean IssueMapper mapper;
    IssueProject project;
    IssueRow row;
    List<IssueHistory> histories;
    final LocalDate today=LocalDate.of(2026,10,23);
    @BeforeEach void setup() {
        project=new IssueProject(1,"Y","RUN"); row=null; histories=new ArrayList<>();
        when(mapper.lockProject(anyLong())).thenAnswer(i-> i.getArgument(0,Long.class)==1?project:null);
        when(mapper.parent(anyLong())).thenAnswer(i->row==null?null:row.prj_id());
        when(mapper.row(anyLong())).thenAnswer(i->row);
        when(mapper.lockIssue(anyLong())).thenAnswer(i->row);
        when(mapper.insert(any())).thenAnswer(i->{
            var w=i.getArgument(0,IssueInsert.class); var b=w.input();
            row=new IssueRow(7,w.projectId(),b.ttl_nm(),b.svrt_cd(),b.chrg_nm(),b.ddln_dt(),b.issue_stts_cd(),b.actn_desc(),w.today(),w.end());
            return 7L;
        });
        doAnswer(i->{
            var c=i.getArgument(0,IssueUpdate.class).changes();
            row=new IssueRow(row.issue_id(),row.prj_id(),(String)c.getOrDefault("ttl_nm",row.ttl_nm()),
                (String)c.getOrDefault("svrt_cd",row.svrt_cd()),(String)c.getOrDefault("chrg_nm",row.chrg_nm()),
                (LocalDate)c.getOrDefault("ddln_dt",row.ddln_dt()),(String)c.getOrDefault("issue_stts_cd",row.issue_stts_cd()),
                (String)c.getOrDefault("actn_desc",row.actn_desc()),row.reg_dt(),(LocalDate)c.getOrDefault("end_dt",row.end_dt()));
            return null;
        }).when(mapper).update(any());
        doAnswer(i->{histories.add(i.getArgument(0));return null;}).when(mapper).history(any());
        when(mapper.list(any())).thenReturn(new ArrayList<>());
        when(mapper.openCount()).thenAnswer(i->row!=null&&!row.issue_stts_cd().equals("CLOSED")?1L:0L);
    }
    Map<String,Object> input(boolean create) {
        var b=new LinkedHashMap<String,Object>(); if(create)b.put("prj_id",1);
        b.put("ttl_nm","검토할 이슈");b.put("svrt_cd","MID");b.put("ddln_dt","2026-01-01");
        b.put("issue_stts_cd","OPEN");b.put("chrg_nm","외부 담당자");b.put("actn_desc","조치 검토");return b;
    }
    ResultActions save(boolean create,Map<String,Object> b)throws Exception {
        return mvc.perform((create?post("/api/issues"):put("/api/issues/7"))
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(b)));
    }
    void create()throws Exception {save(true,input(true)).andExpect(status().isOk());}
    @Test void anonymousRunCreateAcceptsPastDeadlineAndIncrementsOpenCount()throws Exception {
        mvc.perform(get("/api/issues")).andExpect(jsonPath("$.data.open_count").value(0));
        create();assertThat(row.reg_dt()).isEqualTo(today);assertThat(row.end_dt()).isNull();
        mvc.perform(get("/api/issues")).andExpect(status().isOk()).andExpect(jsonPath("$.data.open_count").value(1));
        verify(mapper).insert(argThat(w->w.actor()==null&&w.input().chrg_nm().equals("외부 담당자")));
        assertThat(histories).isEmpty();
    }
    @Test void anonymousHoldCreateIsAllowed()throws Exception {project=new IssueProject(1,"Y","HOLD");create();assertThat(row).isNotNull();}
    @Test void anonymousDoingCreateHasNullEndDate() throws Exception {
        project = new IssueProject(1,"Y","HOLD");
        var body = input(true); body.put("issue_stts_cd","DOING");
        mvc.perform(post("/api/issues").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body))).andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.prj_id").value(1))
            .andExpect(jsonPath("$.data.issue_stts_cd").value("DOING"))
            .andExpect(jsonPath("$.data.end_dt").isEmpty());
        verify(mapper).insert(argThat(w -> w.actor() == null && w.end() == null));
    }
    @Test void anonymousReopenDoingAuditsEndDateAndStateSideEffect() throws Exception {
        var closed = input(true); closed.put("issue_stts_cd","CLOSED"); save(true,closed).andExpect(status().isOk());
        var body = input(false); body.put("issue_stts_cd","DOING");
        mvc.perform(put("/api/issues/7").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.issue_stts_cd").value("DOING"))
            .andExpect(jsonPath("$.data.end_dt").isEmpty());
        assertThat(histories).contains(new IssueHistory("7","end_dt",today.toString(),null,null));
        verify(mapper).history(new IssueHistory("7","issue_stts_cd","CLOSED","DOING",null));
    }
    @Test void putValidationBlankTitleHasErrorBodyAndNoSideEffect() throws Exception {
        create(); var before = row; var body = input(false); body.put("ttl_nm", " ");
        mvc.perform(put("/api/issues/7").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body))).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.field").value("ttl_nm"))
            .andExpect(jsonPath("$.message").value("제목을 넣어 주세요."))
            .andExpect(jsonPath("$.requestId").isNotEmpty());
        assertThat(row).isEqualTo(before); verify(mapper,never()).update(any());
        verify(mapper,never()).history(any());
    }
    @Test void closedCreationUsesServerDatesWithoutOpenCountOrHistory()throws Exception {
        var b=input(true);b.put("issue_stts_cd","CLOSED");save(true,b).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.reg_dt").value(today.toString())).andExpect(jsonPath("$.data.end_dt").value(today.toString()));
        mvc.perform(get("/api/issues")).andExpect(jsonPath("$.data.open_count").value(0));assertThat(histories).isEmpty();
    }
    @ParameterizedTest @CsvSource({"prj_id,프로젝트를 골라 주세요.","ttl_nm,제목을 넣어 주세요.","ddln_dt,기한을 넣어 주세요."})
    void requiredCreateFields(String field,String message)throws Exception {
        var b=input(true);b.remove(field);save(true,b).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.field").value(field)).andExpect(jsonPath("$.message").value(message));
        verify(mapper,never()).insert(any());assertThat(row).isNull();
    }
    @ParameterizedTest @ValueSource(strings={"END","LOST","N"})
    void closedProjectRejectsCreateWithoutWrite(String state)throws Exception {
        project=new IssueProject(1,state.equals("N")?"N":"Y",state.equals("N")?"RUN":state);
        save(true,input(true)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PROJECT_CLOSED"));
        verify(mapper,never()).insert(any());
    }
    @Test void anonymousTitleAndActionUpdateKeepsIdentityAndOnlyChangedColumnHistory()throws Exception {
        create();var b=input(false);b.put("ttl_nm","수정 제목");b.put("actn_desc","수정 조치");
        save(false,b).andExpect(status().isOk()).andExpect(jsonPath("$.data.prj_id").value(1))
            .andExpect(jsonPath("$.data.ttl_nm").value("수정 제목")).andExpect(jsonPath("$.data.actn_desc").value("수정 조치"));
        assertThat(histories).extracting(IssueHistory::column).containsExactly("ttl_nm","actn_desc");
        assertThat(histories.get(0)).isEqualTo(new IssueHistory("7","ttl_nm","검토할 이슈","수정 제목",null));
    }
    @Test void closeThenClosedEditPreservesEndAndReopenClearsIt()throws Exception {
        create();var b=input(false);b.put("issue_stts_cd","CLOSED");b.put("ttl_nm","종료 제목");
        save(false,b).andExpect(status().isOk()).andExpect(jsonPath("$.data.end_dt").value(today.toString()));
        assertThat(histories).extracting(IssueHistory::column).containsExactly("ttl_nm","issue_stts_cd","end_dt");
        histories.clear();save(false,b).andExpect(status().isOk());assertThat(histories).isEmpty();
        b.put("issue_stts_cd","OPEN");save(false,b).andExpect(status().isOk()).andExpect(jsonPath("$.data.end_dt").isEmpty());
        assertThat(histories).contains(new IssueHistory("7","end_dt",today.toString(),null,null));
    }
    @Test void emptyTitleUpdatePreservesOriginal()throws Exception {
        create();var before=row;var b=input(false);b.put("ttl_nm"," ");save(false,b).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.field").value("ttl_nm")).andExpect(jsonPath("$.message").value("제목을 넣어 주세요."));
        assertThat(row).isEqualTo(before);assertThat(histories).isEmpty();
    }
    @Test void endProjectUpdateIsConflictAndUnchanged()throws Exception {
        create();var before=row;project=new IssueProject(1,"Y","END");var b=input(false);b.put("issue_stts_cd","CLOSED");
        save(false,b).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PROJECT_CLOSED"));
        assertThat(row).isEqualTo(before);assertThat(histories).isEmpty();
    }
    @ParameterizedTest @CsvSource({"ttl_nm,101","chrg_nm,51","actn_desc,301"})
    void maximumLengthsForBothWrites(String field,int length)throws Exception {
        create();var before=row;
        for(boolean create:new boolean[]{true,false}) {var b=input(create);b.put(field,"가".repeat(length));
            save(create,b).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field));}
        assertThat(row).isEqualTo(before);
    }
    @ParameterizedTest @CsvSource({"svrt_cd,URGENT","issue_stts_cd,DONE","ddln_dt,2026-02-30"})
    void invalidEnumsAndDateForBothWrites(String field,String value)throws Exception {
        for(boolean create:new boolean[]{true,false}) {var b=input(create);b.put(field,value);
            save(create,b).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field));}
    }
    @Test void immutableProjectAndRegistrationDateAreRejected()throws Exception {
        create();var b=input(false);b.put("prj_id",2);save(false,b).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("prj_id"));
        b.remove("prj_id");b.put("reg_dt","2026-01-01");save(false,b).andExpect(status().isBadRequest());
        verify(mapper,never()).update(any());
    }
    @Test void missingProjectAndIssueReturnNotFound()throws Exception {
        var b=input(true);b.put("prj_id",999);save(true,b).andExpect(status().isNotFound());save(false,input(false)).andExpect(status().isNotFound());
    }
    @ParameterizedTest @CsvSource({"status,ALL","severity,BAD","projectId,0","page,-1","size,30"})
    void invalidListFilters(String field,String value)throws Exception {
        mvc.perform(get("/api/issues").param(field,value)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field));
    }
    @Test void listBindsAndFiltersAndPagingTogether()throws Exception {
        mvc.perform(get("/api/issues").param("status","OVERDUE").param("severity","HIGH").param("projectId","1").param("page","2"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.page").value(2)).andExpect(jsonPath("$.data.size").value(50));
        verify(mapper).list(new IssueQuery("OVERDUE","HIGH",1L,today,2,50,100));
        verify(mapper).count(new IssueQuery("OVERDUE","HIGH",1L,today,2,50,100));
    }
}
