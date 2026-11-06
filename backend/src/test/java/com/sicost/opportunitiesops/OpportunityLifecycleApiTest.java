package com.sicost.opportunitiesops;

import static com.sicost.opportunitiesops.OpportunityLifecycleModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.finance.MonthlyProfitReader;
import com.sicost.projects.*;
import com.sicost.assignments.AssignmentService;
import java.io.ByteArrayInputStream;
import java.math.*;
import java.time.*;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.web.servlet.*;

@WebMvcTest(controllers=OpportunityLifecycleController.class)
@Import({RequestIdFilter.class,ApiExceptionHandler.class,OpportunityLifecycleService.class,OpportunityWinService.class,
    OpportunityQuoteVerifier.class,OpportunityExportService.class,WorkforceForecastService.class,ExcelExportService.class,
    CurrentUser.class,PermissionService.class,OpportunityLifecycleApiTest.Time.class})
class OpportunityLifecycleApiTest {
    @TestConfiguration static class Time {@Bean Clock clock(){return Clock.fixed(Instant.parse("2026-10-22T15:01:00Z"),ZoneId.of("Asia/Seoul"));}}
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;
    @MockBean OpportunityLifecycleMapper mapper;@MockBean OpportunityForecastMapper forecast;
    @MockBean MonthlyProfitReader profit;@MockBean ProjectService projects;@MockBean AssignmentService assignments;
    @MockBean SecurityPermissionMapper security;
    final SecurityPrincipal actor=com.sicost.common.AuthenticatedTestSupport.principal(13L,List.of(4L),DataScope.ALL);
    String status,stage,approval,evidence,validation,lost,rejected;BigDecimal probability;Long project;
    LocalDateTime approvalDate;BigDecimal approvalMargin;List<ApprovalHistory> history;List<Audit> audit;
    List<Need> needs;long revenue,cost;boolean costKnown;boolean exists;
    Row row(){return new Row(7,"O-07","기회",1,1L,null,stage,probability,90000000,"MO","2026-11",4,null,null,null,status,project,
        LocalDateTime.of(2026,10,8,0,0),null,"영업 추정과 견적 차이",approval,revenue,cost,BigDecimal.valueOf(revenue-cost).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(revenue),2,RoundingMode.HALF_UP),approvalMargin,approvalDate,evidence,validation,lost,rejected);}
    Need need(){return new Need(11,"BE","S",2,"2026-11",4,BigDecimal.ONE,10000000L,8000000L,8000000L,10000000L,null);}
    @BeforeEach void setup(){
        when(security.grants(any())).thenReturn(List.of(new SecurityPermissionMapper.GrantRow(4L,"ALL",true,false,null)));
        status="OPEN";stage="DISC";approval="PENDING";probability=BigDecimal.TEN;project=null;approvalDate=null;approvalMargin=null;evidence=null;validation=null;lost=null;rejected=null;
        history=new ArrayList<>();audit=new ArrayList<>();needs=List.of(need());revenue=80000000;cost=64000000;costKnown=true;exists=true;
        when(mapper.lockScoped(anyLong(),any())).thenAnswer(i->exists?row():null);when(mapper.row(anyLong())).thenAnswer(i->row());when(mapper.needs(anyLong())).thenAnswer(i->needs);
        when(mapper.history(anyLong())).thenAnswer(i->List.copyOf(history));
        when(mapper.costSources(any())).thenAnswer(i->costKnown?List.of(new CostSource(4,1,"2026-10",BigDecimal.valueOf(cost/8),BigDecimal.ZERO,"BE","S")):List.of());
        when(mapper.approvalRate(any())).thenReturn(new BigDecimal("15"));
        doAnswer(i->{var c=i.getArgument(0,Change.class);c.values().forEach((key,value)->{switch(key){
            case "oppt_stts_cd"->status=(String)value;case "bsn_stg_cd"->stage=(String)value;case "win_prblt_rt"->probability=(BigDecimal)value;
            case "quot_aprv_stts_cd"->approval=(String)value;case "win_prj_id"->project=(Long)value;case "zero_cost_evidence"->evidence=(String)value;case "zero_cost_validation"->validation=(String)value;
            case "aprv_at"->approvalDate=(LocalDateTime)value;case "aprv_mrgn_rt"->approvalMargin=(BigDecimal)value;case "lost_rsn_desc"->lost=(String)value;case "rjct_rsn_desc"->rejected=(String)value;default->{} }});return null;}).when(mapper).change(any());
        doAnswer(i->{audit.add(i.getArgument(0));return null;}).when(mapper).audit(any());
        doAnswer(i->{var w=i.getArgument(0,ApprovalWrite.class);history.add(new ApprovalHistory(w.action(),w.opinion(),w.actor(),LocalDateTime.of(2026,10,23,0,1),w.margin()));return null;}).when(mapper).approval(any());
        when(forecast.code(any())).thenReturn(true);
        when(projects.create(any())).thenAnswer(i->{var s=i.getArgument(0,ProjectModels.ProjectSave.class);
            var r=new ProjectModels.ProjectRow(31,"P-2601",s.prj_nm(),s.cust_ptnr_id(),s.cntrct_ty_cd(),s.prj_stts_cd(),null,null,s.cntrct_bgn_dt(),s.cntrct_end_dt(),null,s.bsn_part_id(),s.stlm_mthd_cd(),null,"N",null,s.init_cntrct_amt(),null,null,null,null,null,null,s.init_cntrct_amt(),s.cntrct_end_dt(),BigDecimal.ZERO,"Y","고객","파트",null,0);
            return new ProjectModels.ProjectDetail(r,List.of());});
    }
    ResultActions post(String action,Object body)throws Exception{return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/opportunities/7/"+action).principal(actor).contentType("application/json").content(json.writeValueAsString(body)));}
    Map<String,Object> project(){return new LinkedHashMap<>(Map.of("prj_nm","수주 프로젝트","cust_ptnr_id",1,"bsn_part_id",1,"cntrct_ty_cd","MO","prj_stts_cd","RUN","cntrct_bgn_dt","2026-11-01","cntrct_end_dt","2027-02-28","init_cntrct_amt",80000000));}
    void error(ResultActions result,String field)throws Exception{result.andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false)).andExpect(jsonPath("$.field").value(field)).andExpect(jsonPath("$.message").isNotEmpty()).andExpect(jsonPath("$.requestId").isNotEmpty());}
    void untouched(){verify(mapper,never()).change(any());verify(mapper,never()).approval(any());verify(mapper,never()).release(any());verify(mapper,never()).audit(any());verify(projects,never()).create(any());}
    Object payload(String action){return switch(action){case "stage"->Map.of("bsn_stg_cd","PROP");case "win"->Map.of("project",project());case "reject","lose"->Map.of("reason","재검토");default->Map.of();};}
    @ParameterizedTest @ValueSource(strings={"stage","approve","reject","disable","lose","win"})
    void anonymousMutationIs401BeforeBusinessSql(String action)throws Exception{
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/opportunities/7/"+action)
            .contentType("application/json").content(json.writeValueAsString(payload(action))))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        verifyNoInteractions(mapper,security);untouched();
    }
    @ParameterizedTest @ValueSource(strings={"stage","approve","reject","disable","lose","win"})
    void revokedActionIs403BeforeBusinessSql(String action)throws Exception{
        when(security.grants(any())).thenReturn(List.of());
        post(action,payload(action)).andExpect(status().isForbidden());verifyNoInteractions(mapper);untouched();
    }
    @ParameterizedTest @ValueSource(strings={"stage","approve","reject","disable","lose","win"})
    void outOfScopeIs404WithoutSideEffects(String action)throws Exception{
        when(security.grants(any())).thenReturn(List.of(new SecurityPermissionMapper.GrantRow(4L,"PART",false,false,null)));
        when(security.parts(13L)).thenReturn(List.of(2L));
        when(mapper.lockScoped(anyLong(),any())).thenReturn(null);
        post(action,payload(action)).andExpect(status().isNotFound());
        verify(mapper).lockScoped(eq(7L),argThat(s->!s.allows(new ScopePredicate.Target(1L,null,null,null))));untouched();
    }
    @ParameterizedTest @CsvSource({"stage,mdfcn_auth_yn","approve,cnfrm_auth_yn","reject,cnfrm_auth_yn","disable,del_auth_yn","lose,mdfcn_auth_yn","win,cnfrm_auth_yn"})
    void eachMutationUsesItsOwnActionAndUnionScope(String action,String column)throws Exception{
        when(security.grants(any())).thenReturn(List.of(new SecurityPermissionMapper.GrantRow(4L,"PART",false,false,null),new SecurityPermissionMapper.GrantRow(5L,"PROJECT",false,false,null)));
        when(security.parts(13L)).thenReturn(List.of(1L));when(security.projects(13L)).thenReturn(List.of(99L));approval="NONE";
        if(action.equals("approve")||action.equals("reject"))approval="PENDING";
        post(action,payload(action)).andExpect(status().isOk());
        verify(security).grants(argThat(q->q.userId()==13L&&q.screenId().equals("UI-29")&&q.actionColumn().equals(column)));
        verify(mapper).lockScoped(eq(7L),argThat(s->s.allows(new ScopePredicate.Target(1L,null,null,null))&&s.allows(new ScopePredicate.Target(null,99L,null,null))));
    }
    @Test void replacingApproverAndEndDateKeepsTheirActualPreviousValues()throws Exception{
        when(mapper.auditValues(7)).thenReturn(Map.of("aprv_user_id",8L,"end_dt",LocalDate.of(2026,10,1)));
        post("approve",Map.of()).andExpect(status().isOk());
        assertThat(audit).anyMatch(a->a.column().equals("aprv_user_id")&&a.before().equals("8")&&a.after().equals("13"));
        clearInvocations(mapper);audit.clear();post("lose",Map.of("reason","취소")).andExpect(status().isOk());
        assertThat(audit).anyMatch(a->a.column().equals("end_dt")&&a.before().equals("2026-10-01")&&a.after().equals("2026-10-23"));
    }
    @Test void authenticatedStageResetsProbabilityAndWritesBothAuditFields()throws Exception{
        probability=new BigDecimal("55");post("stage",Map.of("bsn_stg_cd","PROP")).andExpect(status().isOk()).andExpect(jsonPath("$.data.bsn_stg_cd").value("PROP")).andExpect(jsonPath("$.data.win_prblt_rt").value(30));
        assertThat(audit).extracting(Audit::column).containsExactlyInAnyOrder("bsn_stg_cd","win_prblt_rt");assertThat(audit).allMatch(a->a.actor().equals(13L));
    }
    @ParameterizedTest @ValueSource(strings={"WON","LOST","X",""})void stageValidation(String value)throws Exception{error(post("stage",Map.of("bsn_stg_cd",value)),"bsn_stg_cd");untouched();}
    @ParameterizedTest @ValueSource(strings={"NEGO","PRE"})void pendingCannotEnterLateStage(String value)throws Exception{post("stage",Map.of("bsn_stg_cd",value)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QUOTE_APPROVAL_REQUIRED"));untouched();}
    @Test void pendingAlreadyPreCannotMoveAway()throws Exception{stage="PRE";post("stage",Map.of("bsn_stg_cd","DISC")).andExpect(status().isConflict());assertThat(stage).isEqualTo("PRE");untouched();}
    @Test void authenticatedApprovalRecordsActorAndSnapshot()throws Exception{post("approve",Map.of("opinion","승인")).andExpect(status().isOk()).andExpect(jsonPath("$.data.quote.approval_state").value("APPROVED")).andExpect(jsonPath("$.data.approval_history[0].user_id").value(13)).andExpect(jsonPath("$.data.approval_history[0].action").value("APPROVE"));assertThat(approvalDate).isNotNull();assertThat(approvalMargin).isEqualByComparingTo("20");}
    @Test void approvalEmptyOpinionAllowed()throws Exception{post("approve",Map.of()).andExpect(status().isOk());}
    @Test void commandResponseUsesSameAmountDifferenceAsOpportunityRead()throws Exception{
        long difference=Math.subtractExact(revenue,row().expc_amt());
        var rate=com.sicost.opportunities.OpportunityQuoteCalculator.rate(BigDecimal.valueOf(difference),row().expc_amt());
        post("stage",Map.of("bsn_stg_cd","PROP")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.quote.amount_difference").value(difference))
            .andExpect(jsonPath("$.data.quote.amount_difference_rate").value(rate.doubleValue()));
    }
    @Test void longApprovalIsRejectedWithoutWrites()throws Exception{error(post("approve",Map.of("opinion","가".repeat(501))),"opinion");untouched();}
    @Test void approvalCannotBeReprocessed()throws Exception{post("approve",Map.of()).andExpect(status().isOk());post("approve",Map.of()).andExpect(status().isConflict());assertThat(history).hasSize(1);}
    @Test void approvalUnknownDoesNotPermitWin()throws Exception{costKnown=false;cost=0;post("approve",Map.of()).andExpect(status().isOk());post("win",Map.of("project",project())).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("COST_UNVERIFIED"));verify(projects,never()).create(any());}
    @Test void realZeroCostEvidenceIsBoundAndInvalidatedBySourceChange()throws Exception{cost=0;post("approve",Map.of("zero_cost_evidence","cst.mth_cst:4")).andExpect(status().isOk()).andExpect(jsonPath("$.data.quote.cost_status").value("PROVEN_ZERO"));assertThat(validation).isNotBlank();costKnown=false;post("win",Map.of("project",project())).andExpect(status().isConflict());verify(projects,never()).create(any());}
    @Test void inventedZeroProofIsRejected()throws Exception{error(post("approve",Map.of("zero_cost_evidence","true")),"zero_cost_evidence");untouched();}
    @Test void staleCostCannotBeApproved()throws Exception{cost=1;post("approve",Map.of()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QUOTE_CHANGED"));untouched();}
    @Test void authenticatedRejectionSavesBusinessReason()throws Exception{mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/opportunities/7/reject").principal(actor).contentType("application/json").content("{\"reason\":\"단가 재검토\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.quote.approval_state").value("REJECTED")).andExpect(jsonPath("$.data.approval_history[0].opinion").value("단가 재검토"));assertThat(rejected).isEqualTo("단가 재검토");}
    @ParameterizedTest @ValueSource(strings={"reject","lose"})void emptyReasonIsRejected(String action)throws Exception{error(post(action,Map.of("reason"," ")),"reason");untouched();}
    @ParameterizedTest @ValueSource(strings={"reject","lose"})void longReasonIsRejected(String action)throws Exception{error(post(action,Map.of("reason","가".repeat(501))),"reason");untouched();}
    @ParameterizedTest @ValueSource(strings={"NONE","APPROVED","REJECTED"})void rejectionRequiresPending(String value)throws Exception{approval=value;post("reject",Map.of("reason","반려")).andExpect(status().isConflict());untouched();}
    @Test void anonymousLossPreservesNeedsAndReleasesTemps()throws Exception{post("lose",Map.of("reason","사업 취소")).andExpect(status().isOk()).andExpect(jsonPath("$.data.oppt_stts_cd").value("LOST")).andExpect(jsonPath("$.data.bsn_stg_cd").value("LOST")).andExpect(jsonPath("$.data.needs[0].need_id").value(11));verify(mapper).lockNeeds(7);verify(mapper).release(any());assertThat(lost).isEqualTo("사업 취소");}
    @Test void authenticatedDisableIsLogicalAndReleasesTemps()throws Exception{mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/opportunities/7/disable").principal(actor).contentType("application/json").content("{}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.oppt_stts_cd").value("DELETED"));verify(mapper).release(any());assertThat(needs).hasSize(1);}
    @ParameterizedTest @CsvSource({"stage,WON","approve,LOST","reject,DELETED","disable,WON","disable,LOST","disable,DELETED","lose,WON","lose,LOST","lose,DELETED","win,WON"})
    void closedSourceIsUnchanged(String action,String value)throws Exception{status=value;Object body=switch(action){case "stage"->Map.of("bsn_stg_cd","PROP");case "approve"->Map.of();case "win"->Map.of("project",project());default->Map.of("reason","종료");};post(action,body).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OPPORTUNITY_CLOSED"));assertThat(status).isEqualTo(value);untouched();}
    @ParameterizedTest @ValueSource(strings={"0","abc","9223372036854775808"})void invalidPath(String id)throws Exception{error(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/opportunities/"+id+"/disable")),"id");untouched();}
    @Test void missingSourceIs404()throws Exception{exists=false;post("stage",Map.of("bsn_stg_cd","PROP")).andExpect(status().isNotFound());untouched();}
    @Test void lossAuditMasksContactText()throws Exception{post("lose",Map.of("reason","010-1234-5678 agent@example.org 취소")).andExpect(status().isOk());assertThat(audit).noneMatch(a->a.after()!=null&&(a.after().contains("1234-5678")||a.after().contains("agent@")));}
    @Test void anonymousWinUsesProjectServiceFinalQuoteAndPerNeedPlans()throws Exception{approval="NONE";post("win",Map.of("project",project())).andExpect(status().isOk()).andExpect(jsonPath("$.data.opportunity.oppt_stts_cd").value("WON")).andExpect(jsonPath("$.data.project.init_cntrct_amt").value(80000000)).andExpect(jsonPath("$.data.converted_assignments").isArray());verify(projects).create(argThat(p->p.init_cntrct_amt()==80000000));verify(mapper,times(4)).plan(argThat(p->p.mm().compareTo(new BigDecimal("2"))==0&&p.need().need_id()==11));}
    @Test void duplicateWinDoesNotCreateSecondProject()throws Exception{approval="NONE";post("win",Map.of("project",project())).andExpect(status().isOk());post("win",Map.of("project",project())).andExpect(status().isConflict());verify(projects,times(1)).create(any());}
    @Test void pendingWinIsBlocked()throws Exception{post("win",Map.of("project",project())).andExpect(status().isConflict());untouched();}
    @Test void changedContractAmountIsNotAccepted()throws Exception{approval="NONE";var p=project();p.put("init_cntrct_amt",90000000);post("win",Map.of("project",p)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QUOTE_AMOUNT_MISMATCH"));untouched();}
    @Test void planOutsideContractIsNotClipped()throws Exception{approval="NONE";var p=project();p.put("cntrct_end_dt","2027-01-31");post("win",Map.of("project",p)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PLAN_OUTSIDE_CONTRACT"));assertThat(needs.get(0).mth_cnt()).isEqualTo(4);untouched();}
    @Test void closedPlanMonthCannotBeWritten()throws Exception{approval="NONE";when(mapper.closedMonths()).thenReturn(List.of("2026-11"));post("win",Map.of("project",project())).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MONTH_CLOSED"));untouched();}
    @Test void reverseContractDatesAre400()throws Exception{var p=project();p.put("cntrct_end_dt","2026-10-01");error(post("win",Map.of("project",p)),"cntrct_end_dt");untouched();}
    @Test void missingMoPartPreservesExistingValidation()throws Exception{var p=project();p.remove("bsn_part_id");error(post("win",Map.of("project",p)),"bsn_part_id");untouched();}
    @ParameterizedTest @CsvSource({"from,2026-13","sort,oppt_nm,asc","page,1","oppt_stts_cd,DELETED","bsn_stg_cd,WON","cust_ptnr_id,0"})void exportValidation(String key,String value)throws Exception{error(mvc.perform(get("/api/opportunities/export").param(key,value)),key);verify(mapper,never()).exportRows(any());untouched();}
    @Test void exportAnonymousReturnsRealWorkbookBeyondCurrentPage()throws Exception{
        List<ExportRow> rows=new ArrayList<>();for(int i=0;i<73;i++)rows.add(new ExportRow(i+1,"O-"+(i+10),"조건 전체 "+i,"고객","파트","PROP",BigDecimal.valueOf(55),100,"MO","2026-11",4,"확인",null,"OPEN","PENDING",null,null,2,LocalDateTime.now()));
        when(mapper.exportRows(any())).thenReturn(rows);
        var response=mvc.perform(get("/api/opportunities/export").principal(actor).param("q","고객").param("from","2026-11")).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.file_name").value("영업파이프라인_20261023.xlsx")).andReturn();
        var data=json.readTree(response.getResponse().getContentAsString()).get("data");
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(Base64.getDecoder().decode(data.get("content_base64").asText())))){assertThat(book.getSheetAt(0).getLastRowNum()).isEqualTo(76);assertThat(book.getSheetAt(0).getRow(76).getCell(2).getStringCellValue()).isEqualTo("고객");}
        verify(mapper).exportRows(argThat(q->q.status().equals("OPEN")&&q.q().equals("고객")&&q.from().equals("2026-11")));verify(mapper).download(argThat(d->d.actor().equals(13L)));untouched();
    }
    @Test void emptyExportHasOnlyHeaders()throws Exception{var response=mvc.perform(get("/api/opportunities/export").principal(actor)).andExpect(status().isOk()).andReturn();var data=json.readTree(response.getResponse().getContentAsString()).get("data");try(var book=new XSSFWorkbook(new ByteArrayInputStream(Base64.getDecoder().decode(data.get("content_base64").asText())))){assertThat(book.getSheetAt(0).getLastRowNum()).isEqualTo(3);}untouched();}
    @ParameterizedTest @CsvSource({"from,2026-13","months,7","year,0","size,10","sort,unknown","page,-1","unknown,x"})void forecastValidation(String key,String value)throws Exception{error(mvc.perform(get("/api/opportunities/forecast").param(key,value)),key);untouched();}
    @Test void anonymousForecastEmptyPastYearReturnsEnvelope()throws Exception{mvc.perform(get("/api/opportunities/forecast").param("year","2025")).andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty()).andExpect(jsonPath("$.data.total").value(0)).andExpect(jsonPath("$.data.workforce.message").value("예측할 미래월이 없습니다."));untouched();}
}
