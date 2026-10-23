package com.sicost.salespartsops;

import static com.sicost.salespartsops.SalesPartModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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

@WebMvcTest(controllers=SalesPartManagementController.class)
@Import({RequestIdFilter.class,ApiExceptionHandler.class,SalesPartManagementService.class,SalesPartExcelService.class,CurrentUser.class,PermissionService.class,SalesPartManagementApiTest.Time.class})
class SalesPartManagementApiTest {
    @TestConfiguration static class Time { @Bean Clock clock(){return Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"),ZoneId.of("Asia/Seoul"));} }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean SalesPartManagementMapper mapper;
    final Map<Long,Part> parts=new LinkedHashMap<>();
    final Map<Long,List<Standard>> standards=new HashMap<>();
    final List<History> history=new ArrayList<>();
    final List<Access> access=new ArrayList<>();
    long sequence=1000;
    Standard standard(String id,String start,String end,String leader,int rate) { return new Standard(id,start,end,leader,BigDecimal.valueOf(rate),1500000,"적용 중"); }
    @BeforeEach void setup() {
        for(long i=1;i<=43;i++) {
            parts.put(i,new Part(Long.toString(i),i==1?"본사":"영업 "+i,i==1?"Y":"N",i==43?"N":"Y",(int)i,0,0));
            standards.put(i,new ArrayList<>(List.of(standard(Long.toString(i),"2026-01",null,"파트장",40))));
        }
        when(mapper.lock(any())).thenAnswer(a->parts.get(a.<Read>getArgument(0).id()));
        when(mapper.part(any())).thenAnswer(a->parts.get(a.<Read>getArgument(0).id()));
        when(mapper.standards(any())).thenAnswer(a->new ArrayList<>(standards.getOrDefault(a.<Read>getArgument(0).id(),List.of())));
        when(mapper.lastClosed()).thenReturn("2026-09");
        when(mapper.options()).thenAnswer(a->parts.values().stream().filter(p->p.use_yn().equals("Y")).map(p->new Option(p.bsn_part_id(),p.bsn_part_nm(),p.hq_yn().equals("Y"))).toList());
        when(mapper.nameExists(any())).thenAnswer(a->{ValueWrite w=a.getArgument(0);return parts.values().stream().anyMatch(p->!p.bsn_part_id().equals(w.id().toString())&&p.bsn_part_nm().equals(w.value()));});
        doAnswer(a->{ValueWrite w=a.getArgument(0);Part p=parts.get(w.id());parts.put(w.id(),new Part(p.bsn_part_id(),w.value(),p.hq_yn(),p.use_yn(),p.sort_seq(),p.peopleCount(),p.projectCount()));return null;}).when(mapper).rename(any());
        doAnswer(a->{ValueWrite w=a.getArgument(0);Part p=parts.get(w.id());parts.put(w.id(),new Part(p.bsn_part_id(),p.bsn_part_nm(),p.hq_yn(),w.value(),p.sort_seq(),p.peopleCount(),p.projectCount()));return null;}).when(mapper).use(any());
        doAnswer(a->{PeriodWrite w=a.getArgument(0);standards.get(w.id()).removeIf(s->s.aply_bgn_ym().compareTo(w.start())>=0&&s.aply_bgn_ym().compareTo(w.month())>0);return null;}).when(mapper).deleteScheduled(any());
        doAnswer(a->{PeriodWrite w=a.getArgument(0);standards.get(w.id()).replaceAll(s->s.aply_bgn_ym().compareTo(w.start())<0&&(s.aply_end_ym()==null||s.aply_end_ym().compareTo(w.start())>=0)?new Standard(s.part_std_hist_id(),s.aply_bgn_ym(),w.previous(),s.ldr_nm(),s.prft_dstrb_rt(),s.bsn_fee_amt(),s.status()):s);return null;}).when(mapper).closePrevious(any());
        when(mapper.insertStandard(any())).thenAnswer(a->{StandardWrite w=a.getArgument(0);long id=sequence++;standards.get(w.id()).add(new Standard(Long.toString(id),w.input().aply_bgn_ym(),null,w.input().ldr_nm(),w.input().prft_dstrb_rt(),w.input().bsn_fee_amt(),"예정"));return id;});
        doAnswer(a->{history.add(a.getArgument(0));return null;}).when(mapper).appendHistory(any());
        doAnswer(a->{access.add(a.getArgument(0));return null;}).when(mapper).appendAccess(any());
        when(mapper.existingIds(anyList())).thenAnswer(a->a.<List<Long>>getArgument(0).stream().filter(parts::containsKey).count());
        when(mapper.exportRows(any())).thenAnswer(a->{ExportQuery q=a.getArgument(0);return parts.values().stream().filter(p->q.ids()==null||q.ids().contains(Long.valueOf(p.bsn_part_id()))).map(p->new ExportRow(p.bsn_part_id(),p.bsn_part_nm(),"파트장",BigDecimal.valueOf(40),1500000L,"2026-01",null,p.peopleCount(),p.projectCount(),p.use_yn(),p.sort_seq())).toList();});
    }
    Map<String,Object> input(String month,int rate) { return new HashMap<>(Map.of("aply_bgn_ym",month,"ldr_nm","파트장","prft_dstrb_rt",rate,"bsn_fee_amt",1500000)); }
    ResultActions save(String id,Map<String,Object> body)throws Exception {return mvc.perform(post("/api/sales-parts/"+id+"/standards").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));}
    ResultActions rename(String id,String name)throws Exception {return mvc.perform(put("/api/sales-parts/"+id+"/name").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("bsn_part_nm",name))));}
    ResultActions state(String id,String action)throws Exception {return mvc.perform(post("/api/sales-parts/"+id+"/"+action));}
    void unchanged() {assertThat(history).isEmpty();verify(mapper,never()).rename(any());verify(mapper,never()).use(any());verify(mapper,never()).deleteScheduled(any());verify(mapper,never()).insertStandard(any());}
    @Test @DisplayName("auth happy business side-effect: options include headquarters and exclude inactive parts without writes")
    void optionsArePublicOrderedAndReadOnly()throws Exception {
        mvc.perform(get("/api/sales-parts/options")).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(42)).andExpect(jsonPath("$.data[0].hq").value(true)).andExpect(jsonPath("$.data[1].id").value("2"));
        unchanged();assertThat(access).isEmpty();
    }
    @Test void emptyOptionsAreAnEmptySuccessArray()throws Exception {parts.clear();mvc.perform(get("/api/sales-parts/options")).andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());}
    @Test @DisplayName("auth happy business side-effect: mst.bsn_part use_yn Y to N and DISABLE history")
    void publicDisableRemovesOptionsAndRecordsDisable()throws Exception {
        mvc.perform(post("/api/sales-parts/2/disable")).andExpect(status().isOk()).andExpect(jsonPath("$.data.use_yn").value("N"));
        mvc.perform(get("/api/sales-parts/options")).andExpect(jsonPath("$.data[1].id").value("3"));
        assertThat(history).singleElement().satisfies(h->{assertThat(h.type()).isEqualTo("DISABLE");assertThat(h.id()).isEqualTo("2");assertThat(h.before()).isEqualTo("Y");assertThat(h.after()).isEqualTo("N");assertThat(h.actor()).isNull();});assertThat(standards.get(2L)).hasSize(1);
    }
    @Test @DisplayName("auth happy business side-effect: mst.bsn_part use_yn N to Y preserves standards")
    void publicEnableRestoresOptionsAndPreservesStandards()throws Exception {
        var before=new ArrayList<>(standards.get(43L));
        mvc.perform(post("/api/sales-parts/43/enable")).andExpect(status().isOk()).andExpect(jsonPath("$.data.use_yn").value("Y")).andExpect(jsonPath("$.data.bsn_part_id").value("43"));
        mvc.perform(get("/api/sales-parts/options")).andExpect(jsonPath("$.data[42].id").value("43"));
        assertThat(standards.get(43L)).isEqualTo(before);assertThat(history).singleElement().satisfies(h->{assertThat(h.type()).isEqualTo("UPDATE");assertThat(h.column()).isEqualTo("use_yn");assertThat(h.before()).isEqualTo("N");assertThat(h.after()).isEqualTo("Y");});
    }
    @ParameterizedTest @CsvSource({"2,enable","43,disable"}) void repeatedStateIsNoWrite(String id,String action)throws Exception {state(id,action).andExpect(status().isOk());unchanged();}
    @Test @DisplayName("validation: nonexistent disable target is 404 without writes")
    void nonexistentDisableIs404()throws Exception {
        mvc.perform(post("/api/sales-parts/999/disable")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(parts).hasSize(43);unchanged();
    }
    @Test @DisplayName("validation: nonexistent enable target is 404 without writes")
    void nonexistentEnableIs404()throws Exception {
        mvc.perform(post("/api/sales-parts/999/enable")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(parts).hasSize(43);unchanged();
    }
    @Test void peopleAloneBlockDisable()throws Exception {
        parts.put(2L,new Part("2","영업 2","N","Y",2,3,0));state("2","disable").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PART_IN_USE")).andExpect(jsonPath("$.message").value("영업 2에 소속 인력 3명, 진행 프로젝트 0건이 있습니다. 먼저 다른 파트로 옮겨 주세요."));unchanged();
    }
    @Test void projectAloneBlocksDisable()throws Exception {parts.put(2L,new Part("2","영업 2","N","Y",2,0,1));state("2","disable").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PART_IN_USE"));unchanged();}
    @Test void hqDisableIsLocked()throws Exception {state("1","disable").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("HQ_PART_LOCKED"));unchanged();}
    @Test @DisplayName("auth happy side-effect: mst.bsn_part bsn_part_nm UPDATE and readback")
    void publicRenameReturnsReadbackAndSingleHistory()throws Exception {
        mvc.perform(put("/api/sales-parts/2/name").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("bsn_part_nm","새 영업파트"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.bsn_part_nm").value("새 영업파트"));
        assertThat(history).singleElement().satisfies(h->{assertThat(h.column()).isEqualTo("bsn_part_nm");assertThat(h.before()).isEqualTo("영업 2");assertThat(h.after()).isEqualTo("새 영업파트");});
    }
    @Test void unchangedNameDoesNotUpdateOrAudit()throws Exception {rename("2","영업 2").andExpect(status().isOk());unchanged();}
    @ParameterizedTest @ValueSource(strings={""," ","1234567890123456789012345678901"})
    void invalidNameHasFieldError(String name)throws Exception {
        mvc.perform(put("/api/sales-parts/2/name").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("bsn_part_nm",name))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("bsn_part_nm")).andExpect(jsonPath("$.message").isNotEmpty());unchanged();
    }
    @Test @DisplayName("business: duplicate part name preserves name and history")
    void duplicateNameIsConflict()throws Exception {
        mvc.perform(put("/api/sales-parts/2/name").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("bsn_part_nm","영업 3"))))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_PART_NAME"));unchanged();
    }
    @Test void hqNameLockPrecedesValidation()throws Exception {rename("1","").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("HQ_PART_LOCKED"));unchanged();}
    @Test void missingNameTargetIs404()throws Exception {rename("999","새 파트").andExpect(status().isNotFound());unchanged();}
    @Test @DisplayName("auth happy business side-effect: INSERT future standard with null aply_end_ym preserves current period")
    void futureStandardClosesPreviousAndReturnsCurrentPlusScheduled()throws Exception {
        mvc.perform(post("/api/sales-parts/2/standards").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input("2026-11",45))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.currentStandard.prft_dstrb_rt").value(40)).andExpect(jsonPath("$.data.scheduledChanges[0].prft_dstrb_rt").value(45)).andExpect(jsonPath("$.data.standards[0].aply_end_ym").value("2026-10"));
        assertThat(standards.get(2L).get(1).aply_end_ym()).isNull();assertThat(history).singleElement().satisfies(h->{assertThat(h.type()).isEqualTo("INSERT");assertThat(h.after()).contains("2026-11","파트장","45","1500000");assertThat(h.actor()).isNull();});
    }
    @Test void replacementDeletesOnlySameOrLaterFutureAndAuditsBoth()throws Exception {
        standards.get(2L).set(0,standard("2","2026-01","2026-10","파트장",40));standards.get(2L).add(standard("50","2026-11",null,"예정 파트장",45));
        save("2",input("2026-11",50)).andExpect(status().isOk());
        assertThat(standards.get(2L)).extracting(Standard::part_std_hist_id).contains("2").doesNotContain("50");
        assertThat(history).extracting(History::type).containsExactly("DELETE","INSERT");assertThat(history.get(0).before()).contains("예정 파트장","45");assertThat(history.get(1).after()).contains("50");
    }
    @Test void laterFutureChangeKeepsNovemberAndClosesItInNovember()throws Exception {
        standards.get(2L).set(0,standard("2","2026-01","2026-10","파트장",40));standards.get(2L).add(standard("50","2026-11",null,"예정 파트장",45));
        save("2",input("2026-12",50)).andExpect(status().isOk());assertThat(standards.get(2L).get(1).aply_end_ym()).isEqualTo("2026-11");assertThat(history).noneMatch(h->h.type().equals("DELETE"));
    }
    @Test void sameEffectiveValuesReturnSameStandard()throws Exception {save("2",input("2026-11",40)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SAME_STANDARD"));unchanged();}
    @Test void closedMonthIsConflictAndUnchanged()throws Exception {save("2",input("2026-09",45)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH"));unchanged();}
    @Test void hqStandardLockPrecedesFieldValidation()throws Exception {save("1",Map.of()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("HQ_PART_LOCKED"));unchanged();}
    @Test void missingStandardTargetIs404()throws Exception {save("999",input("2026-11",45)).andExpect(status().isNotFound());unchanged();}
    @ParameterizedTest @CsvSource({"bsn_fee_amt,0","bsn_fee_amt,1.5","prft_dstrb_rt,100.001","prft_dstrb_rt,-1","prft_dstrb_rt,101"})
    void invalidNumericStandardIs400WithoutHistory(String field,String number)throws Exception {
        var in=input("2026-11",45);in.put(field,new BigDecimal(number));
        mvc.perform(post("/api/sales-parts/2/standards").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(in)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field)).andExpect(jsonPath("$.message").isNotEmpty());unchanged();
    }
    @ParameterizedTest @ValueSource(strings={""," ","123456789012345678901234567890123456789012345678901"})
    void leaderValidation(String leader)throws Exception {var in=input("2026-11",45);in.put("ldr_nm",leader);save("2",in).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("ldr_nm"));unchanged();}
    @ParameterizedTest @ValueSource(strings={"2026-13","0000-01","2026-1",""}) void monthValidation(String month)throws Exception {save("2",input(month,45)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("aply_bgn_ym"));unchanged();}
    @ParameterizedTest @ValueSource(ints={0,100}) void percentageBoundsAreAccepted(int rate)throws Exception {save("2",input("2026-11",rate)).andExpect(status().isOk());}
    @Test void noCloseAcceptsExplicitMonthAndReturnsNullMinimum()throws Exception {when(mapper.lastClosed()).thenReturn(null);save("2",input("2026-11",45)).andExpect(status().isOk()).andExpect(jsonPath("$.data.lastClosedMonth").isEmpty()).andExpect(jsonPath("$.data.firstAllowedMonth").isEmpty());}
    @Test void pastUnclosedStartIsAllowedWithoutDeletingPriorHistory()throws Exception {when(mapper.lastClosed()).thenReturn("2026-07");save("2",input("2026-08",45)).andExpect(status().isOk());assertThat(standards.get(2L).get(0).aply_end_ym()).isEqualTo("2026-07");}
    @Test void sameAlreadyAppliedStartCannotBeReplaced()throws Exception {when(mapper.lastClosed()).thenReturn(null);save("2",input("2026-01",45)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SAME_STANDARD"));unchanged();}
    @Test @DisplayName("auth happy business side-effect: DOWNLOAD access history, business rows unchanged")
    void exportIgnoresCurrentPageIncludesDisabledAndKeepsColumnOrder()throws Exception {
        var result=mvc.perform(get("/api/sales-parts/export").param("columns","bsn_fee_amt,bsn_part_nm,use_yn").param("page","2").param("size","20")).andExpect(status().isOk()).andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).andExpect(header().string("Content-Disposition",org.hamcrest.Matchers.startsWith("attachment; filename*=UTF-8''"))).andReturn();
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(result.getResponse().getContentAsByteArray()))) {
            var sheet=book.getSheetAt(0);assertThat(sheet.getLastRowNum()).isEqualTo(46);assertThat(sheet.getRow(3).getCell(0).getStringCellValue()).isEqualTo("영업비(1인·월)");assertThat(sheet.getRow(4).getCell(0).getCellType()).isEqualTo(CellType.NUMERIC);assertThat(sheet.getRow(4).getCell(0).getCellStyle().getDataFormatString()).isEqualTo("#,##0");assertThat(sheet.getRow(46).getCell(2).getStringCellValue()).isEqualTo("중지");assertThat(sheet.getPaneInformation().getHorizontalSplitPosition()).isEqualTo((short)4);assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).contains("기본 사용자","2026-10-22 09:00");
        }
        assertThat(access).singleElement().satisfies(a->{assertThat(a.filename()).isEqualTo("영업파트_20261022.xlsx");assertThat(a.actor()).isNull();assertThat(a.detail()).contains("\"rowCount\":43");});assertThat(history).isEmpty();
    }
    @Test void selectedExportIsExactlyTwoAcrossPages()throws Exception {
        var result=mvc.perform(get("/api/sales-parts/export").param("columns","bsn_part_nm").param("ids","2,43").param("page","2")).andExpect(status().isOk()).andReturn();
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(result.getResponse().getContentAsByteArray()))) {assertThat(book.getSheetAt(0).getLastRowNum()).isEqualTo(5);assertThat(book.getSheetAt(0).getRow(5).getCell(0).getStringCellValue()).isEqualTo("영업 43");}
    }
    @Test void missingColumnsIs400NotAFileOrAudit()throws Exception {mvc.perform(get("/api/sales-parts/export")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("columns"));assertThat(access).isEmpty();}
    @ParameterizedTest @ValueSource(strings={"bsn_part_nm,bsn_part_nm","unknown","checkbox","currentStandard,currentStandard.ldr_nm"})
    void invalidOrDuplicateColumnsAre400(String columns)throws Exception {mvc.perform(get("/api/sales-parts/export").param("columns",columns)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("columns"));assertThat(access).isEmpty();}
    @Test void missingSelectedIdFailsWithoutFileOrAccess()throws Exception {mvc.perform(get("/api/sales-parts/export").param("columns","bsn_part_nm").param("ids","2,999")).andExpect(status().isNotFound());assertThat(access).isEmpty();verify(mapper,never()).exportRows(any());}
    @ParameterizedTest @ValueSource(strings={"scheduledChanges,asc","unknown,desc","bsn_part_nm;DROP TABLE,asc","bsn_part_nm,up"})
    void unsafeSortIs400(String sort)throws Exception {mvc.perform(get("/api/sales-parts/export").param("columns","bsn_part_nm").param("sort",sort)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("sort"));assertThat(access).isEmpty();}
}
