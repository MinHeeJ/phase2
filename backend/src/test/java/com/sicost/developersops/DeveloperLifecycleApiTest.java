package com.sicost.developersops;

import static com.sicost.developersops.DeveloperLifecycleModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.security.*;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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

@WebMvcTest(controllers=DeveloperLifecycleController.class)
@Import({RequestIdFilter.class,ApiExceptionHandler.class,DeveloperLifecycleService.class,ExcelExportService.class,CurrentUser.class,PermissionService.class,DeveloperLifecycleApiTest.Time.class})
class DeveloperLifecycleApiTest {
    @TestConfiguration static class Time {@Bean Clock clock(){return Clock.fixed(Instant.parse("2026-10-22T15:01:00Z"),ZoneId.of("Asia/Seoul"));}}
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean DeveloperLifecycleMapper mapper;
    final Map<Long,DeveloperState> states=new HashMap<>();
    final List<DeveloperAudit> audits=new ArrayList<>();
    @BeforeEach void setup(){
        states.clear();audits.clear();for(long id=1;id<=3;id++)states.put(id,new DeveloperState(id,"개발자 "+id,"Y"));
        when(mapper.state(anyLong())).thenAnswer(i->states.get(i.getArgument(0,Long.class)));
        when(mapper.detail(any())).thenAnswer(i->{var q=i.getArgument(0,DeveloperDate.class);var row=states.get(q.id());return Map.of("dev_id",q.id(),"dev_nm",row.dev_nm(),"mng_no","K-000"+q.id(),"use_yn",row.use_yn());});
        doAnswer(i->{var w=i.getArgument(0,DeveloperStateWrite.class);var old=states.get(w.id());states.put(w.id(),new DeveloperState(w.id(),old.dev_nm(),w.use()));return null;}).when(mapper).changeState(any());
        doAnswer(i->{audits.add(i.getArgument(0));return null;}).when(mapper).audit(any());
        when(mapper.options(any())).thenAnswer(i->{var q=i.getArgument(0,DeveloperOptionQuery.class);return states.values().stream().filter(s->s.use_yn().equals("Y")).map(s->new DeveloperOption(s.dev_id(),"K-000"+s.dev_id(),s.dev_nm(),"백엔드","FT")).filter(o->q.q()==null||o.mngNo().contains(q.q())||o.name().contains(q.q())).sorted(Comparator.comparing(DeveloperOption::name)).toList();});
    }
    ResultActions disable(Long... ids)throws Exception{return mvc.perform(post("/api/developers/disable").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("ids",ids))));}
    void untouched(){verify(mapper,never()).changeState(any());verify(mapper,never()).audit(any());}
    void error(ResultActions result,String field)throws Exception{result.andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field)).andExpect(jsonPath("$.message").isNotEmpty()).andExpect(jsonPath("$.requestId").isNotEmpty()).andExpect(jsonPath("$.success").value(false));untouched();}
    @Test void optionsAnonymousSearchWireAndReadback()throws Exception{for(int n=0;n<2;n++)mvc.perform(get("/api/developers/options").param("q","K-0003")).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].id").value(3)).andExpect(jsonPath("$.data[0].mngNo").value("K-0003")).andExpect(jsonPath("$.data[0].name").value("개발자 3")).andExpect(jsonPath("$.data[0].mainJob").value("백엔드")).andExpect(jsonPath("$.data[0].empType").value("FT"));untouched();}
    @Test void optionsNeverTruncatesAtFifty()throws Exception{for(long id=4;id<=75;id++)states.put(id,new DeveloperState(id,"개발자 "+id,"Y"));mvc.perform(get("/api/developers/options")).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(75));}
    @Test void optionsRejectsUnknownFilter()throws Exception{error(mvc.perform(get("/api/developers/options").param("page","1")),"page");}
    @Test void anonymousAssignmentsHappyEmptyResponseIsReadOnly()throws Exception {
        mvc.perform(get("/api/developers/1/assignments")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items").isArray()).andExpect(jsonPath("$.data.total").value(0))
            .andExpect(jsonPath("$.data.page").value(0));verify(mapper).assignments(any());untouched();
    }
    @Test void anonymousHistoryHappyEmptyResponseIsReadOnly()throws Exception {
        mvc.perform(get("/api/developers/1/history")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items").isArray()).andExpect(jsonPath("$.data.total").value(0))
            .andExpect(jsonPath("$.data.page").value(0));verify(mapper).history(any());untouched();
    }
    @Test void anonymousMonthlyCostsHappyEmptyResponseIsReadOnly()throws Exception {
        mvc.perform(get("/api/developers/1/monthly-costs")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items").isArray()).andExpect(jsonPath("$.data.total").value(0))
            .andExpect(jsonPath("$.data.page").value(0));verify(mapper).monthlyCosts(any());untouched();
    }
    @Test void enableRetireeBusinessPreservesDateAndExcludesOptions()throws Exception {
        states.put(1L,new DeveloperState(1,"개발자 1","N"));
        doAnswer(i->Map.of("dev_id",1L,"use_yn",states.get(1L).use_yn(),"rsgn_dt",LocalDate.of(2026,10,20))).when(mapper).detail(any());
        doReturn(List.of()).when(mapper).options(any());
        mvc.perform(post("/api/developers/1/enable")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.use_yn").value("Y")).andExpect(jsonPath("$.data.rsgn_dt").value("2026-10-20"));
        mvc.perform(get("/api/developers/options").param("q","K-0001")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data").isEmpty());
        assertThat(audits).containsExactly(new DeveloperAudit(1,"UPDATE","N","Y",null));
        verify(mapper).changeState(new DeveloperStateWrite(1,"Y",null));
    }
    @ParameterizedTest @ValueSource(strings={"assignments","history","monthly-costs"}) void anonymousEmptyPagesReadTwice(String path)throws Exception{for(int n=0;n<2;n++)mvc.perform(get("/api/developers/1/"+path)).andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty()).andExpect(jsonPath("$.data.total").value(0)).andExpect(jsonPath("$.data.page").value(0));untouched();}
    @ParameterizedTest @ValueSource(strings={"assignments","history","monthly-costs"}) void missingDeveloperIs404(String path)throws Exception{mvc.perform(get("/api/developers/99/"+path)).andExpect(status().isNotFound()).andExpect(jsonPath("$.requestId").isNotEmpty());untouched();}
    @ParameterizedTest @ValueSource(strings={"assignments","history","monthly-costs"}) void invalidPage(String path)throws Exception{error(mvc.perform(get("/api/developers/1/"+path).param("page","-1")),"page");}
    @ParameterizedTest @ValueSource(strings={"assignments","history","monthly-costs"}) void invalidSize(String path)throws Exception{error(mvc.perform(get("/api/developers/1/"+path).param("size","10")),"size");}
    @ParameterizedTest @ValueSource(strings={"assignments","history","monthly-costs"}) void invalidSort(String path)throws Exception{error(mvc.perform(get("/api/developers/1/"+path).param("sort","dev_id;drop,desc")),"sort");}
    @ParameterizedTest @ValueSource(strings={"assignments","history","monthly-costs"}) void invalidPath(String path)throws Exception{error(mvc.perform(get("/api/developers/0/"+path)),"id");}
    @Test void invalidAssignmentFilter()throws Exception{error(mvc.perform(get("/api/developers/1/assignments").param("status","WRONG")),"status");}
    @Test void invalidHistoryFilter()throws Exception{error(mvc.perform(get("/api/developers/1/history").param("item","../")),"item");}
    @Test void invalidMonthFilter()throws Exception{error(mvc.perform(get("/api/developers/1/monthly-costs").param("from","2026-13")),"from");}
    @Test void reversedMonthFilter()throws Exception{error(mvc.perform(get("/api/developers/1/monthly-costs").param("from","2026-10").param("to","2026-09")),"to");}
    @Test void assignmentFieldsAndPaging()throws Exception{
        when(mapper.assignments(any())).thenReturn(List.of(new DeveloperAssignment(5,8,"프로젝트","BE","백엔드",LocalDate.of(2025,1,1),LocalDate.of(2025,12,31),new BigDecimal("0.50"),"ENDED")));when(mapper.assignmentCount(any())).thenReturn(21L);
        mvc.perform(get("/api/developers/1/assignments").param("status","ENDED").param("page","1").param("size","20")).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(21)).andExpect(jsonPath("$.data.items[0].prj_nm").value("프로젝트")).andExpect(jsonPath("$.data.items[0].job_name").value("백엔드")).andExpect(jsonPath("$.data.items[0].bgn_dt").value("2025-01-01")).andExpect(jsonPath("$.data.items[0].asgn_rt").value(0.5));
        verify(mapper).assignments(argThat(q->q.offset()==20&&q.filter().equals("ENDED")&&q.order().equals("\"bgn_dt\" desc")));untouched();
    }
    @Test void historyWireAndItemFilter()throws Exception{
        when(mapper.history(any())).thenReturn(List.of(new DeveloperChange(LocalDateTime.of(2026,10,23,0,0),"mst.dev","1","UPDATE","use_yn","N","Y","기본 사용자")));when(mapper.historyCount(any())).thenReturn(1L);
        mvc.perform(get("/api/developers/1/history").param("item","use_yn")).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].before").value("N")).andExpect(jsonPath("$.data.items[0].after").value("Y")).andExpect(jsonPath("$.data.items[0].actor").value("기본 사용자"));verify(mapper).history(argThat(q->q.filter().equals("use_yn")));untouched();
    }
    @Test void salaryMoneyIsPublicAndCalendarWindowIsSeoul()throws Exception{
        when(mapper.monthlyCosts(any())).thenReturn(List.of(new DeveloperMonthlyCost("2026-10","FT",4000000,100000,200000,300000,4600000,3900000)));when(mapper.monthlyCostCount(any())).thenReturn(1L);
        mvc.perform(get("/api/developers/1/monthly-costs")).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].slry_amt").value(4000000)).andExpect(jsonPath("$.data.items[0].indv_lbr_cst_amt").value(4600000)).andExpect(jsonPath("$.data.items[0].real_pay_amt").value(3900000));verify(mapper).monthlyCosts(argThat(q->q.from().equals("2025-11")&&q.to().equals("2026-10")));untouched();
    }
    @Test void disableAnonymousPersistsTwoAndExcludesOptions()throws Exception {
        mvc.perform(post("/api/developers/disable").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("ids", List.of(1L, 2L)))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.disabled_count").value(2));
        assertThat(states.get(1L).use_yn()).isEqualTo("N");
        mvc.perform(get("/api/developers/options")).andExpect(jsonPath("$.data.length()").value(1));
        assertThat(audits).containsExactly(new DeveloperAudit(1,"DISABLE","Y","N",null),
            new DeveloperAudit(2,"DISABLE","Y","N",null));
    }
    @Test void emptyIdsDoesNotWrite()throws Exception{error(disable(),"ids");}
    @ParameterizedTest @ValueSource(strings={"{\"ids\":[0]}","{\"ids\":[null]}","{\"ids\":[\"1\"]}","{\"ids\":[1.5]}","{}"}) void malformedIds(String body)throws Exception{error(mvc.perform(post("/api/developers/disable").contentType(MediaType.APPLICATION_JSON).content(body)),"ids");}
    @Test void missingIdAbortsWholeBatch()throws Exception{disable(1L,99L).andExpect(status().isNotFound());untouched();}
    void block(long id){when(mapper.currentAssignments(new DeveloperDate(id,LocalDate.of(2026,10,23)))).thenReturn(1L);}
    @Test void disableBusinessCurrentAssignmentIsConflictAndUnchanged()throws Exception {
        block(1);
        mvc.perform(post("/api/developers/disable").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("ids", List.of(1L)))))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEVELOPER_IN_USE"))
            .andExpect(jsonPath("$.message").value("현재 투입 1건"));
        assertThat(states.get(1L).use_yn()).isEqualTo("Y");untouched();
    }
    @Test void mixedBatchAuditsOnlyAllowed()throws Exception{block(3);disable(1L,2L,3L).andExpect(status().isOk()).andExpect(jsonPath("$.data.disabled_count").value(2)).andExpect(jsonPath("$.data.blocked_count").value(1)).andExpect(jsonPath("$.data.blocked[0].name").value("개발자 3")).andExpect(jsonPath("$.data.blocked[0].current_assignment_count").value(1));assertThat(audits).extracting(DeveloperAudit::id).containsExactly(1L,2L);assertThat(states.get(3L).use_yn()).isEqualTo("Y");}
    @Test void allBlockedBulkIs200()throws Exception{block(1);block(2);disable(1L,2L).andExpect(status().isOk()).andExpect(jsonPath("$.data.disabled_count").value(0)).andExpect(jsonPath("$.data.blocked_count").value(2));untouched();}
    @Test void deduplicateAndReplayDoNotDuplicateHistory()throws Exception{disable(1L,1L).andExpect(jsonPath("$.data.disabled_count").value(1));disable(1L).andExpect(jsonPath("$.data.disabled_count").value(0));assertThat(audits).hasSize(1);}
    @Test void enableAnonymousReadsStateAndRestoresOptions()throws Exception{states.put(1L,new DeveloperState(1,"개발자 1","N"));mvc.perform(post("/api/developers/1/enable")).andExpect(status().isOk()).andExpect(jsonPath("$.data.dev_id").value(1)).andExpect(jsonPath("$.data.use_yn").value("Y"));mvc.perform(get("/api/developers/options").param("q","K-0001")).andExpect(jsonPath("$.data[0].id").value(1));assertThat(audits).containsExactly(new DeveloperAudit(1,"UPDATE","N","Y",null));}
    @Test void enableReplayIsNoop()throws Exception{mvc.perform(post("/api/developers/1/enable")).andExpect(status().isOk());untouched();}
    @Test void enableZeroIs400()throws Exception{error(mvc.perform(post("/api/developers/0/enable")),"id");}
    @Test void enableMissingIs404()throws Exception{mvc.perform(post("/api/developers/99/enable")).andExpect(status().isNotFound());untouched();}
    @Test void exportAnonymousAllRowsRealWorkbookAndFilename()throws Exception{
        var rows=new ArrayList<Map<String,Object>>();for(int n=0;n<101;n++)rows.add(Map.of("dev_nm","=이름","mng_no","K-"+n,"emp_type_name","정규직","main_job_name","백엔드","current_month_mm",new BigDecimal("1.20")));
        when(mapper.exportRows(any())).thenReturn(rows);
        for(int n=0;n<2;n++){
            var response=mvc.perform(get("/api/developers/export").param("empType","FT").param("q","이름")).andExpect(status().isOk()).andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).andReturn().getResponse();
            assertThat(java.net.URLDecoder.decode(response.getHeader("Content-Disposition"),java.nio.charset.StandardCharsets.UTF_8)).contains("개발자풀_20261023.xlsx");
            try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))){var sheet=workbook.getSheetAt(0);assertThat(sheet.getLastRowNum()).isEqualTo(104);assertThat(sheet.getRow(4).getCell(3).getStringCellValue()).isEqualTo("정규직");assertThat(sheet.getRow(4).getCell(9).getNumericCellValue()).isEqualTo(1.2);assertThat(sheet.getRow(4).getCell(0).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.STRING);}
        }
        verify(mapper,times(2)).exportRows(argThat(q->q.empType().equals("FT")&&q.q().equals("이름")&&q.use_yn().equals("Y")));untouched();
    }
    @Test void exportInvalidEmployeeType()throws Exception{error(mvc.perform(get("/api/developers/export").param("empType","BAD")),"empType");}
    @Test void exportInvalidStatus()throws Exception{error(mvc.perform(get("/api/developers/export").param("status","BAD")),"status");}
    @Test void exportInvalidUseScope()throws Exception{error(mvc.perform(get("/api/developers/export").param("use_yn","ALL")),"use_yn");}
    @Test void exportRejectsPaginationRatherThanTruncating()throws Exception{error(mvc.perform(get("/api/developers/export").param("page","1")),"page");}
}
