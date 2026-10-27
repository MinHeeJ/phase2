package com.sicost.projectsops;

import static com.sicost.projectsops.ProjectLifecycleModels.*;
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
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers=ProjectLifecycleController.class)
@Import({RequestIdFilter.class,ApiExceptionHandler.class,ProjectLifecycleService.class,ExcelExportService.class,CurrentUser.class,PermissionService.class,ProjectLifecycleApiTest.Time.class})
class ProjectLifecycleApiTest {
    @TestConfiguration static class Time {@Bean Clock clock(){return Clock.fixed(Instant.parse("2026-10-22T15:01:00Z"),ZoneId.of("Asia/Seoul"));}}
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean ProjectLifecycleMapper mapper;
    final Map<Long,ProjectState> states=new HashMap<>();
    final List<ProjectAudit> audits=new ArrayList<>();
    @BeforeEach void setup(){
        states.clear();audits.clear();states.put(1L,new ProjectState(1,"Y"));
        when(mapper.state(anyLong())).thenAnswer(i->states.get(i.getArgument(0,Long.class)));
        when(mapper.lockState(anyLong())).thenAnswer(i->states.get(i.getArgument(0,Long.class)));
        when(mapper.usage(anyLong())).thenReturn(new ProjectUsage(0,0,0,0));
        when(mapper.detail(any())).thenAnswer(i->{var q=i.getArgument(0,ProjectRead.class);return Map.of("prj_id",q.id(),"prj_no","P-2601","prj_nm","프로젝트","cntrct_ty_cd","TK","prj_stts_cd","RUN","assignmentCount",0,"use_yn",states.get(q.id()).useYn(),"free_sprt_yn","N");});
        doAnswer(i->{var w=i.getArgument(0,ProjectStateWrite.class);states.put(w.id(),new ProjectState(w.id(),w.useYn()));return null;}).when(mapper).changeState(any());
        doAnswer(i->{audits.add(i.getArgument(0));return null;}).when(mapper).audit(any());
        when(mapper.options(any())).thenAnswer(i->states.values().stream().filter(s->"Y".equals(s.useYn())).map(s->new ProjectOption(s.id(),"P-2601","프로젝트","TK",null)).toList());
        when(mapper.history(anyString())).thenAnswer(i->audits.stream().map(a->new ProjectHistoryEntry(LocalDateTime.of(2026,10,23,0,1),a.type().equals("DISABLE")?"DISABLE":"use_yn",a.before(),a.after(),"기본 사용자")).toList());
    }
    void untouched(){verify(mapper,never()).changeState(any());verify(mapper,never()).audit(any());}
    @Test void disableAnonymousReadsNAndHistoryAndExcludesOptions()throws Exception{
        mvc.perform(post("/api/projects/1/disable")).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.use_yn").value("N"));
        mvc.perform(get("/api/projects/options")).andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(get("/api/projects/1/history")).andExpect(jsonPath("$.data[0].field").value("DISABLE")).andExpect(jsonPath("$.data[0].before").value("Y")).andExpect(jsonPath("$.data[0].after").value("N"));
        assertThat(audits).containsExactly(new ProjectAudit("1","DISABLE","Y","N",null));
    }
    @Test void enableAnonymousRestoresOptionsAndRecordsOnlyUseFlag()throws Exception{
        states.put(1L,new ProjectState(1,"N"));
        mvc.perform(post("/api/projects/1/enable")).andExpect(status().isOk()).andExpect(jsonPath("$.data.use_yn").value("Y"));
        mvc.perform(get("/api/projects/options")).andExpect(jsonPath("$.data[0].id").value(1)).andExpect(jsonPath("$.data[0].no").value("P-2601"));
        mvc.perform(get("/api/projects/1/history")).andExpect(jsonPath("$.data[0].field").value("use_yn")).andExpect(jsonPath("$.data[0].before").value("N"));
        assertThat(audits).containsExactly(new ProjectAudit("1","UPDATE","N","Y",null));
        verify(mapper).changeState(new ProjectStateWrite(1,"Y",null));verify(mapper,never()).usage(anyLong());
    }
    @Test void disableRepeatDoesNotWriteTwice()throws Exception{mvc.perform(post("/api/projects/1/disable"));mvc.perform(post("/api/projects/1/disable")).andExpect(status().isOk());assertThat(audits).hasSize(1);}
    @Test void enableAlreadyEnabledIsNoop()throws Exception{mvc.perform(post("/api/projects/1/enable")).andExpect(status().isOk());untouched();}
    @ParameterizedTest @ValueSource(strings={"disable","enable"}) void zeroIdIs400AndUnchanged(String operation)throws Exception{mvc.perform(post("/api/projects/0/"+operation)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("id"));untouched();}
    @ParameterizedTest @ValueSource(strings={"disable","enable"}) void missingIdIs404AndUnchanged(String operation)throws Exception{mvc.perform(post("/api/projects/99/"+operation)).andExpect(status().isNotFound());untouched();}
    @ParameterizedTest @ValueSource(ints={0,1,2,3}) void eachKindIndependentlyBlocksDisable(int kind)throws Exception{
        long[] counts=new long[4];counts[kind]=2;when(mapper.usage(1)).thenReturn(new ProjectUsage(counts[0],counts[1],counts[2],counts[3]));
        var result=mvc.perform(post("/api/projects/1/disable")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PROJECT_IN_USE")).andReturn();
        String message=json.readTree(result.getResponse().getContentAsByteArray()).get("message").asText();
        assertThat(message).contains("투입 "+counts[0]+"건","청구 단계 "+counts[1]+"건","배분표 "+counts[2]+"건","직접 경비 "+counts[3]+"건");
        assertThat(states.get(1L).useYn()).isEqualTo("Y");untouched();
    }
    @Test void anonymousOptionsUsesUnpagedArrayAndQ()throws Exception{mvc.perform(get("/api/projects/options").param("q","프로젝트")).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data[0].type").value("TK"));verify(mapper).options("프로젝트");untouched();}
    @Test void anonymousAssignmentsPreserveDisplayFieldsAndExposeAssignmentIdentity()throws Exception{
        when(mapper.assignments(1)).thenReturn(List.of(new ProjectAssignment("개발자","백엔드",LocalDate.of(2026,1,1),LocalDate.of(2026,12,31),new BigDecimal("0.50"),"ACTIVE",7)));
        var response=mvc.perform(get("/api/projects/1/assignments")).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data[0].beginDate").value("2026-01-01")).andExpect(jsonPath("$.data[0].assignmentRate").value(0.5)).andReturn();
        var row=json.readTree(response.getResponse().getContentAsString()).path("data").get(0);
        assertThat(row.size()).isEqualTo(7);
        assertThat(row.path("asgn_id").asLong()).isEqualTo(7);
        assertThat(row.has("salary")).isFalse();
        assertThat(row.has("bill_untprc_amt")).isFalse();
        untouched();
    }
    @ParameterizedTest @ValueSource(strings={"assignments","history"}) void readEmptyArrays(String operation)throws Exception{mvc.perform(get("/api/projects/1/"+operation)).andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());untouched();}
    @ParameterizedTest @ValueSource(strings={"assignments","history"}) void readMissing(String operation)throws Exception{mvc.perform(get("/api/projects/99/"+operation)).andExpect(status().isNotFound());untouched();}
    @ParameterizedTest @ValueSource(strings={"assignments","history"}) void readInvalidId(String operation)throws Exception{mvc.perform(get("/api/projects/0/"+operation)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("id"));untouched();}
    @Test void exportAnonymousRealWorkbookAllRowsSeoulFilename()throws Exception{
        var rows=new ArrayList<Map<String,Object>>();for(int n=0;n<101;n++)rows.add(Map.of("prj_no","P-2601","prj_nm","=이름","cur_cntrct_amt",100L));when(mapper.exportRows(any())).thenReturn(rows);
        var response=mvc.perform(get("/api/projects/export").param("type","TK").param("q","이름").param("part","NONE").param("sort","prj_nm,asc")).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.file_name").value("프로젝트_2026_20261023.xlsx")).andExpect(jsonPath("$.data.media_type").value(DownloadArtifact.XLSX)).andReturn();
        byte[] bytes=Base64.getDecoder().decode(json.readTree(response.getResponse().getContentAsString()).path("data").path("content_base64").asText());
        try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(bytes))){var sheet=workbook.getSheetAt(0);assertThat(sheet.getLastRowNum()).isEqualTo(104);assertThat(sheet.getRow(4).getCell(1).getStringCellValue()).isEqualTo("=이름");assertThat(sheet.getRow(4).getCell(1).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.STRING);}
        verify(mapper).exportRows(argThat(q->q.year()==2026&&q.type().equals("TK")&&q.unassigned()&&q.order().equals("\"prj_nm\" asc NULLS LAST, prj_id ASC")));untouched();
    }
    @ParameterizedTest @ValueSource(strings={"year","type","status","part","sort"}) void exportInvalidFilters(String field)throws Exception{mvc.perform(get("/api/projects/export").param(field,"invalid")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field));untouched();}
    @Test void exportRejectsPagination()throws Exception{mvc.perform(get("/api/projects/export").param("page","1")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("page"));untouched();}
}
