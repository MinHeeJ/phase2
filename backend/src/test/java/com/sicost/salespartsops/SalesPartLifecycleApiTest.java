package com.sicost.salespartsops;

import static com.sicost.salespartsops.SalesPartLifecycleModels.*;
import static com.sicost.salesparts.SalesPartModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.security.*;
import com.sicost.salesparts.*;
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

@WebMvcTest(controllers=SalesPartLifecycleController.class)
@Import({RequestIdFilter.class,ApiExceptionHandler.class,SalesPartLifecycleService.class,SalesPartExportService.class,SalesPartService.class,ExcelExportService.class,CurrentUser.class,PermissionService.class,SalesPartLifecycleApiTest.Time.class})
class SalesPartLifecycleApiTest {
    @TestConfiguration static class Time { @Bean Clock clock(){return Clock.fixed(Instant.parse("2026-10-22T15:01:00Z"),ZoneId.of("Asia/Seoul"));} }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean SalesPartLifecycleMapper mapper;
    @MockBean SalesPartMapper reads;
    PartRow normal=new PartRow("2","영업 파트","N","Y",1,0,0);
    PartRow stopped=new PartRow("3","중지 파트","N","N",2,0,0);
    SalesPartStandard standard(String id,String begin,String end,String rate) {return new SalesPartStandard(id,begin,end,"파트장",new BigDecimal(rate),1500000,"예정");}
    @BeforeEach void setup() {
        when(mapper.lock(eq(1L),any())).thenReturn(new PartRow("1","본사","Y","Y",0,0,0));
        when(mapper.lock(eq(2L),any())).thenReturn(normal);when(mapper.lock(eq(3L),any())).thenReturn(stopped);
        when(reads.part(eq(2L),any())).thenReturn(normal);when(reads.part(eq(3L),any())).thenReturn(stopped);
        when(reads.lastClosed()).thenReturn("2026-09");when(reads.standards(anyLong(),anyString())).thenReturn(List.of(standard("10","2026-01",null,"40")));
        when(mapper.insertStandard(any())).thenReturn(11L);
    }
    Map<String,Object> valid() {return new LinkedHashMap<>(Map.of("aply_bgn_ym","2026-11","ldr_nm","파트장","prft_dstrb_rt",45,"bsn_fee_amt",1500000));}
    ResultActions save(String id,Map<String,Object> value)throws Exception {return mvc.perform(post("/api/sales-parts/{id}/standards",id).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(value)));}
    ResultActions name(String id,String name)throws Exception {return mvc.perform(put("/api/sales-parts/{id}/name",id).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("bsn_part_nm",name))));}
    void untouched() {verify(mapper,never()).state(any());verify(mapper,never()).rename(any());verify(mapper,never()).deleteScheduled(any());verify(mapper,never()).closePrevious(any());verify(mapper,never()).insertStandard(any());verify(mapper,never()).audit(any());verify(mapper,never()).access(any());}
    @Test void anonymousOptionsIncludeHeadquartersAndHaveNoSideEffects()throws Exception {
        when(mapper.options()).thenReturn(List.of(new SalesPartOption("1","본사",true),new SalesPartOption("2","영업 파트",false)));
        mvc.perform(get("/api/sales-parts/options")).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2)).andExpect(jsonPath("$.data[0].hq").value(true)).andExpect(jsonPath("$.data[1].id").value("2"));untouched();
    }
    @Test void emptyOptionsIsAnArrayWithoutInventedClientErrors()throws Exception {when(mapper.options()).thenReturn(List.of());mvc.perform(get("/api/sales-parts/options")).andExpect(status().isOk()).andExpect(jsonPath("$.data").isArray()).andExpect(jsonPath("$.data").isEmpty());untouched();}
    @Test void anonymousDisableReturnsUpdatedDetailAndDisableAudit()throws Exception {
        when(reads.part(eq(2L),any())).thenReturn(new PartRow("2",normal.bsn_part_nm(),"N","N",1,0,0));
        mvc.perform(post("/api/sales-parts/2/disable")).andExpect(status().isOk()).andExpect(jsonPath("$.data.use_yn").value("N"));
        verify(mapper).state(new PartValueWrite(2,"N",null));verify(mapper).audit(new SalesPartAudit("mst.bsn_part","2","DISABLE","use_yn","Y","N",null));
    }
    @Test void anonymousEnableReturnsSameIdentityAndUpdateAudit()throws Exception {
        when(reads.part(eq(3L),any())).thenReturn(new PartRow("3",stopped.bsn_part_nm(),"N","Y",2,0,0));
        mvc.perform(post("/api/sales-parts/3/enable")).andExpect(status().isOk()).andExpect(jsonPath("$.data.bsn_part_id").value("3")).andExpect(jsonPath("$.data.use_yn").value("Y")).andExpect(jsonPath("$.data.standards.length()").value(1));
        verify(mapper).state(new PartValueWrite(3,"Y",null));verify(mapper).audit(new SalesPartAudit("mst.bsn_part","3","UPDATE","use_yn","N","Y",null));
    }
    @ParameterizedTest @ValueSource(strings={"disable","enable"}) void missingStateTargetIs404(String action)throws Exception {mvc.perform(post("/api/sales-parts/999/"+action)).andExpect(status().isNotFound());untouched();}
    @Test void peopleAloneBlockDisable()throws Exception {when(mapper.lock(eq(2L),any())).thenReturn(new PartRow("2","영업 파트","N","Y",1,3,0));mvc.perform(post("/api/sales-parts/2/disable")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PART_IN_USE")).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("3명")));untouched();}
    @Test void projectAloneBlocksDisable()throws Exception {when(mapper.lock(eq(2L),any())).thenReturn(new PartRow("2","영업 파트","N","Y",1,0,1));mvc.perform(post("/api/sales-parts/2/disable")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PART_IN_USE")).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("1건")));untouched();}
    @Test void headquartersDisableIsLockedBeforeNoop()throws Exception {when(mapper.lock(eq(1L),any())).thenReturn(new PartRow("1","본사","Y","N",0,0,0));mvc.perform(post("/api/sales-parts/1/disable")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("HQ_PART_LOCKED"));untouched();}
    @Test void repeatedDisableHasNoWrites()throws Exception {mvc.perform(post("/api/sales-parts/3/disable")).andExpect(status().isOk());untouched();}
    @Test void repeatedEnableHasNoWrites()throws Exception {mvc.perform(post("/api/sales-parts/2/enable")).andExpect(status().isOk());untouched();}
    @Test void anonymousRenameAuditsOnlyNameAndReadsBack()throws Exception {
        when(reads.part(eq(2L),any())).thenReturn(new PartRow("2","새 이름","N","Y",1,0,0));
        mvc.perform(put("/api/sales-parts/2/name").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("bsn_part_nm","새 이름"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.bsn_part_nm").value("새 이름"));
        verify(mapper).rename(new PartValueWrite(2,"새 이름",null));verify(mapper).audit(new SalesPartAudit("mst.bsn_part","2","UPDATE","bsn_part_nm","영업 파트","새 이름",null));
    }
    @Test void unchangedNameDoesNotUpdateOrAudit()throws Exception {name("2","영업 파트").andExpect(status().isOk());untouched();}
    @ParameterizedTest @ValueSource(ints={0,31}) void invalidNameLength(int length)throws Exception {name("2","가".repeat(length)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("bsn_part_nm"));untouched();}
    @Test void duplicateNameIncludingStoppedIs409()throws Exception {when(mapper.nameExists(2,"중지 파트")).thenReturn(true);name("2","중지 파트").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_PART_NAME"));untouched();}
    @Test void racingUniqueNameIsTranslated()throws Exception {doThrow(new org.springframework.dao.DuplicateKeyException("unique")).when(mapper).rename(any());name("2","새 이름").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_PART_NAME"));verify(mapper,never()).audit(any());}
    @Test void headquartersNameIsLockedEvenIfSame()throws Exception {name("1","본사").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("HQ_PART_LOCKED"));untouched();}
    @Test void missingRenameIs404()throws Exception {name("999","새 이름").andExpect(status().isNotFound());untouched();}
    @ParameterizedTest @ValueSource(ints={1,30}) void nameLengthBoundsReturnSameIdentityAndOneAudit(int length)throws Exception {
        String value="가".repeat(length);
        when(reads.part(eq(2L),any())).thenReturn(new PartRow("2",value,"N","Y",1,0,0));
        name("2",value).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.bsn_part_id").value("2")).andExpect(jsonPath("$.data.bsn_part_nm").value(value));
        verify(mapper).rename(new PartValueWrite(2,value,null));
        verify(mapper).audit(new SalesPartAudit("mst.bsn_part","2","UPDATE","bsn_part_nm","영업 파트",value,null));
        verify(mapper,never()).state(any());verify(mapper,never()).deleteScheduled(any());
        verify(mapper,never()).closePrevious(any());verify(mapper,never()).insertStandard(any());
    }
    @Test void overlongNameHasLengthErrorNotMissingNameError()throws Exception {
        name("2","가".repeat(31)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("bsn_part_nm"))
            .andExpect(jsonPath("$.message").value("파트명은 30자까지 입력할 수 있습니다."));untouched();
    }
    @ParameterizedTest @ValueSource(strings={"{}","{\"bsn_part_nm\":null}","{\"bsn_part_nm\":12}","{\"bsn_part_nm\":\"   \"}"})
    void missingOrNonTextNameHasRequiredFieldError(String body)throws Exception {
        mvc.perform(put("/api/sales-parts/2/name").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("bsn_part_nm"))
            .andExpect(jsonPath("$.message").value("파트명을 입력해 주세요."));untouched();
    }
    @Test void whitespaceAroundSameNameDoesNotWrite()throws Exception {
        name("2","  영업 파트  ").andExpect(status().isOk()).andExpect(jsonPath("$.data.bsn_part_nm").value("영업 파트"));untouched();
    }
    @Test void headquartersDifferentNameIsLockedWithoutDuplicateLookup()throws Exception {
        name("1","변경 본사").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("HQ_PART_LOCKED"));
        untouched();verify(mapper,never()).nameExists(anyLong(),anyString());
    }
    @Test void anonymousStandardClosesPreviousThenInsertsAndReturnsDetail()throws Exception {
        mvc.perform(post("/api/sales-parts/2/standards").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(valid())))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.bsn_part_id").value("2"));
        var order=inOrder(mapper);order.verify(mapper).lock(2,LocalDate.of(2026,10,23));order.verify(mapper).deleteScheduled(any());
        order.verify(mapper).closePrevious(argThat(w->w.previousMonth().equals("2026-10")));order.verify(mapper).insertStandard(argThat(w->w.input().prft_dstrb_rt().compareTo(new BigDecimal("45"))==0));
        order.verify(mapper).audit(argThat(a->a.type().equals("INSERT")&&a.recordId().equals("11")&&a.after().contains("2026-11")&&a.actor()==null));
    }
    @ParameterizedTest @CsvSource({"prft_dstrb_rt,100.001","prft_dstrb_rt,-1","prft_dstrb_rt,101","bsn_fee_amt,0","bsn_fee_amt,1.5"}) void invalidStandardNumbersDoNotWrite(String field,String value)throws Exception {var input=valid();input.put(field,new BigDecimal(value));save("2",input).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field));untouched();}
    @ParameterizedTest @ValueSource(strings={"aply_bgn_ym","ldr_nm","prft_dstrb_rt","bsn_fee_amt"}) void requiredStandardFields(String field)throws Exception {var input=valid();input.remove(field);save("2",input).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field));untouched();}
    @Test void blankLeaderIs400()throws Exception {var input=valid();input.put("ldr_nm","");save("2",input).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("ldr_nm"));untouched();}
    @Test void excessiveLeaderIs400()throws Exception {var input=valid();input.put("ldr_nm","가".repeat(51));save("2",input).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("ldr_nm"));untouched();}
    @ParameterizedTest @ValueSource(strings={"2026-13","2026-1","0000-01"}) void invalidMonths(String month)throws Exception {var input=valid();input.put("aply_bgn_ym",month);save("2",input).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("aply_bgn_ym"));untouched();}
    @Test void sameEffectiveStandardIs409WithoutDeletingScheduled()throws Exception {var input=valid();input.put("prft_dstrb_rt",40);save("2",input).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SAME_STANDARD"));untouched();}
    @Test void comparesWithScheduledStandardEffectiveAtStartNotTodaysStandard()throws Exception {
        when(reads.standards(eq(2L),anyString())).thenReturn(List.of(standard("11","2026-11",null,"45.00"),standard("10","2026-01","2026-10","40")));
        save("2",valid()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SAME_STANDARD"))
            .andExpect(jsonPath("$.message").value("지금 기준과 같습니다. 바꿀 값을 입력해 주세요."));
        untouched();
    }
    @Test void canReplaceScheduledStandardWithTodaysValuesWhenStartValuesDiffer()throws Exception {
        when(reads.standards(eq(2L),anyString())).thenReturn(List.of(standard("11","2026-11",null,"45"),standard("10","2026-01","2026-10","40")));
        var input=valid();input.put("prft_dstrb_rt",40);
        save("2",input).andExpect(status().isOk());
        verify(mapper).audit(argThat(a->a.type().equals("DELETE")&&a.recordId().equals("11")&&a.before().contains("45")));
        verify(mapper).insertStandard(argThat(w->w.input().prft_dstrb_rt().compareTo(new BigDecimal("40"))==0));
    }
    @Test void leaderOnlyChangeCreatesANewStandard()throws Exception {
        var input=valid();input.put("prft_dstrb_rt",40);input.put("ldr_nm"," 새 파트장 ");
        save("2",input).andExpect(status().isOk());
        verify(mapper).insertStandard(argThat(w->w.input().ldr_nm().equals("새 파트장")));
    }
    @Test void feeOnlyChangeCreatesANewStandard()throws Exception {
        var input=valid();input.put("prft_dstrb_rt",40);input.put("bsn_fee_amt",1);
        save("2",input).andExpect(status().isOk());
        verify(mapper).insertStandard(argThat(w->w.input().bsn_fee_amt()==1));
    }
    @ParameterizedTest @ValueSource(ints={0,100}) void inclusiveRateBoundsCreateStandard(int rate)throws Exception {
        var input=valid();input.put("prft_dstrb_rt",rate);
        save("2",input).andExpect(status().isOk());
        verify(mapper).insertStandard(argThat(w->w.input().prft_dstrb_rt().compareTo(BigDecimal.valueOf(rate))==0));
    }
    @ParameterizedTest @ValueSource(strings={"id","bsn_part_nm","hq_yn","use_yn","ldr_user_id","created_by"})
    void standardCannotMutateServerOwnedOrNameFields(String field)throws Exception {
        var input=valid();input.put(field,"1");
        save("2",input).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field));untouched();
    }
    @Test void currentMonthReplacementIsRejectedBeforeAnyHistoryMutation()throws Exception {
        when(reads.lastClosed()).thenReturn(null);
        when(reads.standards(eq(2L),anyString())).thenReturn(List.of(standard("11","2026-10",null,"40"),standard("10","2026-01","2026-09","35")));
        var input=valid();input.put("aply_bgn_ym","2026-10");
        save("2",input).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("aply_bgn_ym"));untouched();
    }
    @Test void closedMonthIs409()throws Exception {var input=valid();input.put("aply_bgn_ym","2026-09");save("2",input).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH"));untouched();}
    @Test void headquartersStandardIs409()throws Exception {save("1",valid()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("HQ_PART_LOCKED"));untouched();}
    @Test void missingStandardTargetIs404()throws Exception {save("999",valid()).andExpect(status().isNotFound());untouched();}
    @Test void replacementAuditsDeletedSummaryBeforeInsertedSummary()throws Exception {
        when(reads.standards(eq(2L),anyString())).thenReturn(List.of(standard("12","2026-12",null,"50"),standard("11","2026-11","2026-11","42"),standard("10","2026-01","2026-10","40")));
        save("2",valid()).andExpect(status().isOk());
        verify(mapper,times(2)).audit(argThat(a->a.type().equals("DELETE")&&a.before().contains("2026-1")&&a.after()==null));
        verify(mapper).audit(argThat(a->a.type().equals("INSERT")&&a.after().contains("45")));
    }
    @Test void decemberChangeKeepsNovember()throws Exception {when(reads.standards(eq(2L),anyString())).thenReturn(List.of(standard("11","2026-11",null,"42")));var input=valid();input.put("aply_bgn_ym","2026-12");save("2",input).andExpect(status().isOk());verify(mapper,never()).audit(argThat(a->a.type().equals("DELETE")));verify(mapper).closePrevious(argThat(w->w.previousMonth().equals("2026-11")));}
    @Test void startedReplacementIsRefusedWithoutClosingHistory()throws Exception {when(reads.lastClosed()).thenReturn(null);var input=valid();input.put("aply_bgn_ym","2026-01");save("2",input).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("aply_bgn_ym")).andExpect(jsonPath("$.message").value("적용 중이거나 지난 기준은 대체할 수 없습니다."));untouched();}
    @Test void noClosingAllowsPastStartWithoutDeletingStartedRows()throws Exception {when(reads.lastClosed()).thenReturn(null);when(reads.standards(anyLong(),anyString())).thenReturn(List.of());var input=valid();input.put("aply_bgn_ym","2025-01");save("2",input).andExpect(status().isOk());}
    @Test void foreignKeyDeleteIs400AndDoesNotContinue()throws Exception {doThrow(new org.springframework.dao.DataIntegrityViolationException("fk",new java.sql.SQLException("foreign key","23503"))).when(mapper).deleteScheduled(any());save("2",valid()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("aply_bgn_ym"));verify(mapper,never()).closePrevious(any());verify(mapper,never()).audit(any());}
    @Test void exportAnonymousIsRealXlsxWithRequestedOrderAndAllRows()throws Exception {
        var rows=new ArrayList<Map<String,Object>>();for(int i=1;i<=101;i++)rows.add(Map.of("bsn_part_id",String.valueOf(i),"bsn_part_nm","파트 "+i,"currentStandard.bsn_fee_amt",1500000,"use_yn",i==101?"N":"Y"));
        when(mapper.exportRows(any())).thenReturn(rows);
        var response=mvc.perform(get("/api/sales-parts/export").param("columns","currentStandard.bsn_fee_amt","bsn_part_nm","use_yn")).andExpect(status().isOk()).andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).andExpect(header().string("Content-Disposition",org.hamcrest.Matchers.startsWith("attachment; filename*=UTF-8''"))).andReturn().getResponse();
        try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            var sheet=workbook.getSheetAt(0);assertThat(sheet.getLastRowNum()).isEqualTo(104);assertThat(sheet.getRow(3).getCell(0).getStringCellValue()).isEqualTo("영업비(1인·월)");
            assertThat(sheet.getRow(3).getCell(1).getStringCellValue()).isEqualTo("파트명");assertThat(sheet.getRow(4).getCell(0).getCellType()).isEqualTo(CellType.NUMERIC);assertThat(sheet.getRow(4).getCell(0).getCellStyle().getDataFormatString()).isEqualTo("#,##0");assertThat(sheet.getRow(104).getCell(2).getStringCellValue()).isEqualTo("N");assertThat(sheet.getPaneInformation().isFreezePane()).isTrue();assertThat(workbook.getFontAt(sheet.getRow(3).getCell(0).getCellStyle().getFontIndex()).getBold()).isTrue();assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).contains("기본 사용자","2026-10-23 00:01");
        }
        verify(mapper).exportRows(argThat(q->q.ids()==null&&q.month().equals("2026-10")));
        verify(mapper).access(argThat(a->a.filename().equals("영업파트_20261023.xlsx")&&a.actor()==null&&a.detail().contains("columns")&&a.detail().contains("ids")));
        verify(mapper,never()).audit(any());
    }
    @Test void selectedExportHasTwoRowsWithoutPageRestriction()throws Exception {when(mapper.exportRows(any())).thenReturn(List.of(Map.of("bsn_part_nm","하나"),Map.of("bsn_part_nm","둘")));var response=mvc.perform(get("/api/sales-parts/export").param("columns","bsn_part_nm").param("ids","2","101")).andExpect(status().isOk()).andReturn().getResponse();try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))){assertThat(workbook.getSheetAt(0).getLastRowNum()).isEqualTo(5);}verify(mapper).exportRows(argThat(q->q.ids().equals(List.of(2L,101L))));}
    @Test void missingExportColumnsIs400WithoutDownloadAudit()throws Exception {mvc.perform(get("/api/sales-parts/export")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("columns"));untouched();}
    @Test void validationMissingDisableTargetDoesNotCreatePartOrHistory()throws Exception {
        mvc.perform(post("/api/sales-parts/999/disable")).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND")).andExpect(jsonPath("$.success").value(false));
        untouched();verifyNoInteractions(reads);
    }
    @Test void validationInvalidStandardHasLiteralPathAndNoSideEffect()throws Exception {
        var input=valid();input.put("bsn_fee_amt",0);
        mvc.perform(post("/api/sales-parts/2/standards").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("bsn_fee_amt"))
            .andExpect(jsonPath("$.message").isNotEmpty());untouched();
    }
    @Test void validationInvalidNameHasLiteralPathAndNoSideEffect()throws Exception {
        mvc.perform(put("/api/sales-parts/2/name").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("bsn_part_nm",""))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("bsn_part_nm"))
            .andExpect(jsonPath("$.message").isNotEmpty());untouched();
    }
    @ParameterizedTest @ValueSource(strings={"file_path_addr","currentStandard","bsn_fee_amt","../../secret"}) void invalidExportColumns(String column)throws Exception {mvc.perform(get("/api/sales-parts/export").param("columns",column)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("columns"));untouched();}
    @Test void nonexistentSelectedIdRejectsWholeFile()throws Exception {when(mapper.exportRows(any())).thenReturn(List.of(Map.of("bsn_part_nm","하나")));mvc.perform(get("/api/sales-parts/export").param("columns","bsn_part_nm").param("ids","2","999")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("ids"));untouched();}
    @ParameterizedTest @ValueSource(strings={"","abc","-1","9223372036854775808"}) void invalidExportIds(String id)throws Exception {mvc.perform(get("/api/sales-parts/export").param("columns","bsn_part_nm").param("ids",id)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("ids"));untouched();}
}
