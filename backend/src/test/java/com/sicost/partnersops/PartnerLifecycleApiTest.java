package com.sicost.partnersops;

import static com.sicost.partnersops.PartnerLifecycleModels.*;
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
import org.apache.poi.ss.usermodel.*;
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

@WebMvcTest(controllers=PartnerLifecycleController.class)
@Import({RequestIdFilter.class,ApiExceptionHandler.class,PartnerLifecycleService.class,ExcelExportService.class,CurrentUser.class,PermissionService.class,PartnerLifecycleApiTest.Time.class})
class PartnerLifecycleApiTest {
    @TestConfiguration static class Time { @Bean Clock clock(){return Clock.fixed(Instant.parse("2026-10-22T15:01:00Z"),ZoneId.of("Asia/Seoul"));} }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean PartnerLifecycleMapper mapper;
    PartnerDetail detail(String id,String use){return new PartnerDetail(id,"C-"+id,"BOTH","협력사 B","123-45-67890",null,null,null,null,null,"a@example.test","004","001-23456789","협력사 B",use);}
    @BeforeEach void setup(){
        for(String id:List.of("1","2","3")) {
            when(mapper.detail(id)).thenReturn(detail(id,"Y"));when(mapper.lock(id)).thenReturn(detail(id,"Y"));
            when(mapper.block(new PartnerDateQuery(id,LocalDate.of(2026,10,23)))).thenReturn(new PartnerBlock(id,"협력사 B",0,0,""));
        }
    }
    ResultActions disable(String... ids)throws Exception {return mvc.perform(post("/api/partners/disable").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("ids",ids))));}
    void untouched(){verify(mapper,never()).changeState(any());verify(mapper,never()).audit(any());verify(mapper,never()).access(any());}
    @Test void optionsPublicReturnsWireShapeAndDoesNotWrite()throws Exception {
        when(mapper.options("CUST")).thenReturn(List.of(new PartnerOption("1","C-001","고객사"),new PartnerOption("2","C-002","둘 다")));
        mvc.perform(get("/api/partners/options").param("kind","CUST")).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value("1")).andExpect(jsonPath("$.data[0].no").value("C-001")).andExpect(jsonPath("$.data[1].name").value("둘 다"));untouched();
    }
    @Test void vendorOptionsAnonymous()throws Exception {when(mapper.options("VEND")).thenReturn(List.of(new PartnerOption("1","C-001","협력사 B")));mvc.perform(get("/api/partners/options").param("kind","VEND")).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].name").value("협력사 B"));untouched();}
    @ParameterizedTest @ValueSource(strings={"BOTH","INVALID",""}) void invalidOptionKind(String kind)throws Exception {mvc.perform(get("/api/partners/options").param("kind",kind)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("kind"));untouched();}
    @Test void missingOptionKind()throws Exception {mvc.perform(get("/api/partners/options")).andExpect(status().isBadRequest());untouched();}
    @Test void assignmentsAnonymousUsesSeoulClockAndWireTypes()throws Exception {
        var rows=List.of(new PartnerAssignment("가 프로젝트","가 개발자","협력사 B","BE","백엔드",BigDecimal.ONE,LocalDate.of(2026,12,31)),new PartnerAssignment("나 프로젝트","나 개발자","협력사 B","BE","백엔드",new BigDecimal("0.5"),LocalDate.of(2026,12,31)));
        when(mapper.assignments(new PartnerDateQuery("1",LocalDate.of(2026,10,23)))).thenReturn(rows);
        mvc.perform(get("/api/partners/1/assignments")).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2)).andExpect(jsonPath("$.data[1].affiliation").value("협력사 B")).andExpect(jsonPath("$.data[1].assignmentRate").value(0.5)).andExpect(jsonPath("$.data[1].endDate").value("2026-12-31"));untouched();
    }
    @Test void assignmentMissingPartner()throws Exception {mvc.perform(get("/api/partners/99/assignments")).andExpect(status().isNotFound());verify(mapper,never()).assignments(any());untouched();}
    @Test void historyAnonymousHasNullFieldsAndDefaultActor()throws Exception {
        when(mapper.history("1")).thenReturn(List.of(new PartnerChange(LocalDateTime.of(2026,10,23,0,0),null,"거래처",null,null,"INSERT","기본 사용자")));
        mvc.perform(get("/api/partners/1/history")).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].actorName").value("기본 사용자")).andExpect(jsonPath("$.data[0].itemName").value("거래처"));untouched();
    }
    @Test void historyMissingPartner()throws Exception {mvc.perform(get("/api/partners/99/history")).andExpect(status().isNotFound());verify(mapper,never()).history(any());untouched();}
    @Test void disableAnonymousTwoAndAudits()throws Exception {disable("1","2").andExpect(status().isOk()).andExpect(jsonPath("$.data.disabledIds[0]").value("1")).andExpect(jsonPath("$.data.disabledIds[1]").value("2"));verify(mapper,times(2)).changeState(argThat(w->w.use().equals("N")&&w.actor()==null));verify(mapper,times(2)).audit(argThat(w->w.type().equals("DISABLE")&&w.before().equals("Y")&&w.after().equals("N")&&w.actor()==null));}
    @Test void emptyIdsAre400WithoutChanges()throws Exception {disable().andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("ids"));untouched();}
    @ParameterizedTest @ValueSource(strings={"0","-1","abc","9223372036854775808"}) void invalidIdDoesNotWrite(String id)throws Exception {disable(id).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("ids"));untouched();}
    @Test void missingIdRejectsWholeBatchBeforeMutation()throws Exception {disable("1","99").andExpect(status().isNotFound());untouched();}
    void block(String id,long projects,long people){when(mapper.block(new PartnerDateQuery(id,LocalDate.of(2026,10,23)))).thenReturn(new PartnerBlock(id,"협력사 B",projects,people,""));}
    @Test void mixedBatchOnlyUpdatesAllowed()throws Exception {block("2",0,3);disable("1","2","3").andExpect(status().isOk()).andExpect(jsonPath("$.data.disabledIds.length()").value(2)).andExpect(jsonPath("$.data.blocked[0].peopleCount").value(3));verify(mapper,never()).changeState(argThat(w->w.id().equals("2")));verify(mapper,times(2)).audit(any());}
    @Test void singlePeopleBlockIs409()throws Exception {block("2",0,3);disable("2").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PARTNER_IN_USE")).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("3명")));untouched();}
    @Test void singleProjectBlockIs409()throws Exception {block("1",1,0);disable("1").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PARTNER_IN_USE"));untouched();}
    @Test void allBlockedBatchIs200WithoutMutation()throws Exception {block("1",1,0);block("2",0,3);disable("1","2").andExpect(status().isOk()).andExpect(jsonPath("$.data.disabledIds").isEmpty()).andExpect(jsonPath("$.data.blocked.length()").value(2));untouched();}
    @Test void stoppedAndDuplicateIdsAreNoopOrDeduplicated()throws Exception {when(mapper.lock("2")).thenReturn(detail("2","N"));disable("2","1","1").andExpect(status().isOk()).andExpect(jsonPath("$.data.disabledIds.length()").value(1));verify(mapper,times(1)).audit(any());verify(mapper,times(1)).changeState(any());}
    @Test void alreadyStoppedOnlyDoesNotAudit()throws Exception {when(mapper.lock("1")).thenReturn(detail("1","N"));disable("1").andExpect(status().isOk()).andExpect(jsonPath("$.data.disabledIds").isEmpty());untouched();}
    @Test void anonymousDisableHappyStateAndSideEffectAreExplicit()throws Exception {
        mvc.perform(post("/api/partners/disable").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("ids",List.of("1","2")))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.disabledIds.length()").value(2))
            .andExpect(jsonPath("$.data.blocked").isEmpty());
        verify(mapper).changeState(new PartnerStateWrite("1","N",null));
        verify(mapper).changeState(new PartnerStateWrite("2","N",null));
        verify(mapper).audit(new PartnerAudit("1","DISABLE","Y","N",null));
        verify(mapper).audit(new PartnerAudit("2","DISABLE","Y","N",null));
        verify(mapper,never()).access(any());
    }
    @Test void validationDisableEmptyIdsHasNoStateOrAuditChange()throws Exception {
        mvc.perform(post("/api/partners/disable").contentType(MediaType.APPLICATION_JSON).content("{\"ids\":[]}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("ids"))
            .andExpect(jsonPath("$.message").isNotEmpty());untouched();
    }
    @Test void enablePublicPreservesIdentityAndAuditsUpdate()throws Exception {when(mapper.lock("1")).thenReturn(detail("1","N"));mvc.perform(post("/api/partners/1/enable")).andExpect(status().isOk()).andExpect(jsonPath("$.data.ptnr_id").value("1")).andExpect(jsonPath("$.data.ptnr_no").value("C-1")).andExpect(jsonPath("$.data.use_yn").value("Y"));verify(mapper).changeState(new PartnerStateWrite("1","Y",null));verify(mapper).audit(new PartnerAudit("1","UPDATE","N","Y",null));}
    @Test void repeatedEnableNoop()throws Exception {mvc.perform(post("/api/partners/1/enable")).andExpect(status().isOk());untouched();}
    @Test void missingEnableDoesNotCreate()throws Exception {mvc.perform(post("/api/partners/99/enable")).andExpect(status().isNotFound());untouched();}
    @Test void exportMissingColumnsIs400()throws Exception {mvc.perform(get("/api/partners/export")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("columns"));untouched();}
    @ParameterizedTest @ValueSource(strings={"../../account","ptnr_id","rmrk_desc","file_path_addr"}) void exportRejectsUnknownColumns(String col)throws Exception {mvc.perform(get("/api/partners/export").param("columns",col)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("columns"));untouched();}
    List<Map<String,Object>> exportRows(int count){var rows=new ArrayList<Map<String,Object>>();for(int i=1;i<=count;i++)rows.add(Map.of("ptnr_id",""+i,"ptnr_nm","상호 "+i,"biz_reg_no","123-45-67890","bank_cd","은행","acnt_no","001-23456789","dpstr_nm","예금주","projectCount",2));return rows;}
    @Test void exportAnonymousAllRowsColumnOrderStylesRawAccountAndAudit()throws Exception {
        when(mapper.exportRows(any())).thenReturn(exportRows(101));
        var response=mvc.perform(get("/api/partners/export").param("columns","ptnr_nm","paymentAccount","biz_reg_no","projectCount","acnt_no").param("q","상호")).andExpect(status().isOk()).andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).andExpect(header().string("Content-Disposition",org.hamcrest.Matchers.startsWith("attachment; filename*=UTF-8''"))).andReturn().getResponse();
        try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            var sheet=workbook.getSheetAt(0);assertThat(sheet.getLastRowNum()).isEqualTo(104);
            assertThat(sheet.getRow(3).getCell(0).getStringCellValue()).isEqualTo("상호");assertThat(sheet.getRow(3).getLastCellNum()).isEqualTo((short)6);
            assertThat(sheet.getRow(4).getCell(2).getStringCellValue()).isEqualTo("001-23456789");assertThat(sheet.getRow(4).getCell(2).getCellType()).isEqualTo(CellType.STRING);
            assertThat(sheet.getRow(4).getCell(4).getCellType()).isEqualTo(CellType.STRING);assertThat(sheet.getRow(4).getCell(5).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(sheet.getRow(4).getCell(5).getCellStyle().getDataFormatString()).isEqualTo("#,##0");
            assertThat(sheet.getPaneInformation().getHorizontalSplitPosition()).isEqualTo((short)4);assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).contains("조회 조건:","상호");assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).contains("기본 사용자","2026-10-23 00:01");
        }
        verify(mapper).access(argThat(a->a.filename().equals("거래처_20261023.xlsx")&&a.actor()==null&&a.detail().contains("columns")&&!a.detail().contains("001-23456789")));verify(mapper,never()).changeState(any());verify(mapper,never()).audit(any());
    }
    @Test void exportTrimsSearchLikePartnerList()throws Exception {
        when(mapper.exportRows(any())).thenReturn(exportRows(1));
        mvc.perform(get("/api/partners/export").param("columns","ptnr_nm").param("q","  상호  ")).andExpect(status().isOk());
        verify(mapper).exportRows(argThat(q->q.q().equals("상호")));
    }
    @Test void selectedExportIsIndependentOfPage()throws Exception {when(mapper.exportRows(any())).thenReturn(exportRows(2));mvc.perform(get("/api/partners/export").param("columns","ptnr_nm").param("ids","1","2")).andExpect(status().isOk());verify(mapper).exportRows(argThat(q->q.ids().equals(List.of("1","2"))));}
    @Test void selectedOutOfFilterRejectsFileAndAudit()throws Exception {when(mapper.exportRows(any())).thenReturn(exportRows(1));mvc.perform(get("/api/partners/export").param("columns","ptnr_nm").param("ids","1","2")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("ids"));untouched();}
    @Test void formulaLookingTextIsNeverFormula()throws Exception {var rows=new ArrayList<Map<String,Object>>();rows.add(Map.of("ptnr_nm","=WEBSERVICE(\"https://invalid.test\")"));when(mapper.exportRows(any())).thenReturn(rows);var response=mvc.perform(get("/api/partners/export").param("columns","ptnr_nm")).andExpect(status().isOk()).andReturn().getResponse();try(var w=new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))){assertThat(w.getSheetAt(0).getRow(4).getCell(0).getCellType()).isEqualTo(CellType.STRING);}}
}
