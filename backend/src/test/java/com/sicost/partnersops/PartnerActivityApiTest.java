package com.sicost.partnersops;

import static com.sicost.partnersops.PartnerActivityModels.*;
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
import java.util.stream.IntStream;
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

@WebMvcTest(controllers=PartnerActivityController.class)
@Import({RequestIdFilter.class,ApiExceptionHandler.class,PartnerActivityService.class,CurrentUser.class,PermissionService.class,ExcelExportService.class,PartnerActivityApiTest.Time.class})
class PartnerActivityApiTest {
    @TestConfiguration static class Time { @Bean Clock clock(){return Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"),ZoneId.of("Asia/Seoul"));} }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean PartnerActivityMapper mapper;
    PartnerRecord row(String id,String use) {return new PartnerRecord(id,"C-"+id,"BOTH","협력사 "+id,"123-45-67890",null,"SI",null,null,null,"a@example.test","KB","012-345-678901","예금주",use);}
    @BeforeEach void setup() {
        for(String id:List.of("1","2","3")) {when(mapper.partner(id)).thenReturn(row(id,"Y"));when(mapper.lockPartner(id)).thenReturn(row(id,"Y"));}
        when(mapper.counts(any())).thenReturn(new PartnerCounts(0,0));
    }
    ResultActions disable(String... ids)throws Exception {return mvc.perform(post("/api/partners/disable").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("ids",ids))));}
    void unchanged() {verify(mapper,never()).state(any());verify(mapper,never()).appendHistory(any());verify(mapper,never()).download(any());}
    @Test void publicOptionsReturnExactWireKeysAndDoNotWrite()throws Exception {
        when(mapper.options("CUST")).thenReturn(List.of(new PartnerOption("1","C-001","가나다"),new PartnerOption("2","C-002","협력사 B")));
        mvc.perform(get("/api/partners/options").param("kind","CUST")).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value("1")).andExpect(jsonPath("$.data[0].no").value("C-001")).andExpect(jsonPath("$.data[0].name").value("가나다")).andExpect(header().exists("X-Request-Id"));unchanged();
    }
    @Test void publicVendorOptions()throws Exception {when(mapper.options("VEND")).thenReturn(List.of(new PartnerOption("2","C-002","협력사 B")));mvc.perform(get("/api/partners/options").param("kind","VEND")).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].name").value("협력사 B"));unchanged();}
    @ParameterizedTest @ValueSource(strings={"BOTH","INVALID",""}) void rejectsOptionKind(String kind)throws Exception {mvc.perform(get("/api/partners/options").param("kind",kind)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("kind"));verify(mapper,never()).options(any());unchanged();}
    @Test void missingOptionKind()throws Exception {mvc.perform(get("/api/partners/options")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("kind"));}
    @Test void currentAssignmentsPublicAndReadOnly()throws Exception {
        when(mapper.assignments(any())).thenReturn(List.of(new PartnerAssignment("프로젝트 A","이름 A","협력사 B","BE","백엔드",BigDecimal.ONE,LocalDate.parse("2026-10-31")),new PartnerAssignment("프로젝트 B","이름 B","협력사 B","BE","백엔드",new BigDecimal("0.50"),LocalDate.parse("2026-10-31"))));
        mvc.perform(get("/api/partners/1/assignments")).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2)).andExpect(jsonPath("$.data[0].affiliation").value("협력사 B")).andExpect(jsonPath("$.data[0].assignmentRate").value(1));
        verify(mapper).assignments(new PartnerDateQuery("1",LocalDate.parse("2026-10-22")));unchanged();
    }
    @Test void missingAssignmentPartner()throws Exception {mvc.perform(get("/api/partners/99/assignments")).andExpect(status().isNotFound());verify(mapper,never()).assignments(any());unchanged();}
    @Test void publicHistoryPreservesNullKeysAndDefaultActor()throws Exception {
        when(mapper.history("1")).thenReturn(List.of(new PartnerChange(LocalDateTime.parse("2026-10-22T12:00:00"),null,"등록",null,null,"INSERT","기본 사용자")));
        var result=mvc.perform(get("/api/partners/1/history")).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].actorName").value("기본 사용자")).andReturn();
        var data=json.readTree(result.getResponse().getContentAsString()).get("data").get(0);assertThat(data.has("column")).isTrue();assertThat(data.has("before")).isTrue();unchanged();
    }
    @Test void missingHistoryPartner()throws Exception {mvc.perform(get("/api/partners/99/history")).andExpect(status().isNotFound());verify(mapper,never()).history(any());unchanged();}
    @Test void twoAllowedIdsAreDisabledAndAuditedWithNullActor()throws Exception {
        mvc.perform(post("/api/partners/disable").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("ids",List.of("1","2")))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.disabledIds[0]").value("1"))
            .andExpect(jsonPath("$.data.disabledIds[1]").value("2")).andExpect(jsonPath("$.data.blocked").isEmpty());
        for(String id:List.of("1","2")) {verify(mapper).state(new PartnerStateWrite(id,"N",null));verify(mapper).appendHistory(new PartnerHistoryWrite(id,"DISABLE","Y","N",null));}
    }
    @Test void duplicateIdsOnlyWriteOnce()throws Exception {disable("1","1").andExpect(status().isOk()).andExpect(jsonPath("$.data.disabledIds.length()").value(1));verify(mapper,times(1)).state(any());verify(mapper,times(1)).appendHistory(any());}
    @Test void emptyIdsRejectedBeforeAnyWrite()throws Exception {disable().andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("ids"));unchanged();}
    @ParameterizedTest @ValueSource(strings={"{}","{\"ids\":[1]}","{\"ids\":[\"\"]}","{\"ids\":[\"1\"],\"use_yn\":\"N\"}"})
    void malformedDisableDoesNotWrite(String body)throws Exception {mvc.perform(post("/api/partners/disable").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());unchanged();}
    @Test void missingIdRollsBackWholeBatchBeforeUpdates()throws Exception {disable("1","99").andExpect(status().isNotFound());unchanged();}
    @Test void mixedBatchDisablesOnlyTwoAndReturnsThreePeopleReason()throws Exception {
        when(mapper.counts(argThat(q->q!=null&&q.id().equals("3")))).thenReturn(new PartnerCounts(0,3));
        disable("1","2","3").andExpect(status().isOk()).andExpect(jsonPath("$.data.disabledIds.length()").value(2)).andExpect(jsonPath("$.data.blocked[0].peopleCount").value(3));verify(mapper,never()).state(argThat(w->w.id().equals("3")));verify(mapper,times(2)).appendHistory(any());
    }
    @Test void singleBlockedIs409WithCountsAndNoWrites()throws Exception {when(mapper.counts(any())).thenReturn(new PartnerCounts(2,3));disable("1").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PARTNER_IN_USE")).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("소속 인력 3명")));unchanged();}
    @Test void allBlockedBatchStill200NoWrites()throws Exception {when(mapper.counts(any())).thenReturn(new PartnerCounts(1,0));disable("1","2").andExpect(status().isOk()).andExpect(jsonPath("$.data.disabledIds").isEmpty()).andExpect(jsonPath("$.data.blocked.length()").value(2));unchanged();}
    @Test void alreadyDisabledNoAudit()throws Exception {when(mapper.lockPartner("1")).thenReturn(row("1","N"));disable("1").andExpect(status().isOk()).andExpect(jsonPath("$.data.disabledIds").isEmpty());unchanged();}
    @Test void enableIsPublicPreservesIdentityAndAuditsUpdate()throws Exception {
        when(mapper.lockPartner("1")).thenReturn(row("1","N"));when(mapper.partner("1")).thenReturn(row("1","Y"));
        mvc.perform(post("/api/partners/1/enable")).andExpect(status().isOk()).andExpect(jsonPath("$.data.ptnr_id").value("1")).andExpect(jsonPath("$.data.ptnr_no").value("C-1")).andExpect(jsonPath("$.data.use_yn").value("Y")).andExpect(jsonPath("$.data.acnt_no").value("012-345-678901"));verify(mapper).appendHistory(new PartnerHistoryWrite("1","UPDATE","N","Y",null));
    }
    @Test void enableMissingDoesNotCreate()throws Exception {mvc.perform(post("/api/partners/99/enable")).andExpect(status().isNotFound());unchanged();}
    @Test void enableAlreadyActiveNoAudit()throws Exception {mvc.perform(post("/api/partners/1/enable")).andExpect(status().isOk());unchanged();}
    @Test void enableCustomerOmitsAccountKeys()throws Exception {when(mapper.lockPartner("1")).thenReturn(new PartnerRecord("1","C-001","CUST","고객사","123-45-67890",null,null,null,null,null,"a@example.test",null,null,null,"Y"));mvc.perform(post("/api/partners/1/enable")).andExpect(status().isOk()).andExpect(jsonPath("$.data.bank_cd").doesNotExist());unchanged();}
    Map<String,Object> exportRow(int i) {return new HashMap<>(Map.of("ptnr_id",String.valueOf(i),"ptnr_nm","거래처 "+i,"biz_reg_no","123-45-67890","bank_cd","국민은행","acnt_no","012-345-678901","dpstr_nm","예금주"));}
    @Test void exportWholeThreePagesInColumnOrderAndRealXlsx()throws Exception {
        when(mapper.exportRows(any())).thenReturn(IntStream.rangeClosed(1,105).mapToObj(this::exportRow).toList());
        var result=mvc.perform(get("/api/partners/export").param("page","2").param("size","50").param("columns","biz_reg_no","ptnr_nm")).andExpect(status().isOk()).andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).andExpect(header().string("Content-Disposition",org.hamcrest.Matchers.startsWith("attachment; filename*=UTF-8''"))).andReturn();
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(result.getResponse().getContentAsByteArray()))) {
            var sheet=book.getSheetAt(0);assertThat(sheet.getLastRowNum()).isEqualTo(108);assertThat(sheet.getRow(3).getCell(0).getStringCellValue()).isEqualTo("사업자번호");assertThat(sheet.getRow(4).getCell(0).getCellType()).isEqualTo(CellType.STRING);assertThat(sheet.getPaneInformation().getHorizontalSplitPosition()).isEqualTo((short)4);
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).contains("기본 사용자","2026-10-22");
        }
        verify(mapper).download(argThat(w->w.actor()==null&&w.filename().equals("거래처_20261022.xlsx")&&w.detail().contains("105")));
    }
    @Test void selectedIdsIntersectFiltersAndAccountsAreFullTextCells()throws Exception {
        when(mapper.exportRows(any())).thenReturn(List.of(exportRow(1),exportRow(2),exportRow(3)));
        var result=mvc.perform(get("/api/partners/export").param("columns","paymentAccount","ptnr_nm").param("ids","1","3")).andExpect(status().isOk()).andReturn();
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(result.getResponse().getContentAsByteArray()))) {
            var s=book.getSheetAt(0);assertThat(s.getLastRowNum()).isEqualTo(5);assertThat(s.getRow(3).getCell(0).getStringCellValue()).isEqualTo("은행");assertThat(s.getRow(3).getCell(1).getStringCellValue()).isEqualTo("계좌번호");assertThat(s.getRow(4).getCell(1).getStringCellValue()).isEqualTo("012-345-678901");assertThat(s.getRow(4).getCell(1).getCellType()).isEqualTo(CellType.STRING);
        }
        verify(mapper,times(1)).download(any());
    }
    @Test void requiredColumnsMissingNoFileOrAudit()throws Exception {mvc.perform(get("/api/partners/export")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("columns"));verify(mapper,never()).exportRows(any());unchanged();}
    @ParameterizedTest @ValueSource(strings={"","unknown","acnt_no"}) void invalidColumnsNoFile(String value)throws Exception {mvc.perform(get("/api/partners/export").param("columns",value)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("columns"));unchanged();}
    @Test void duplicateColumnsNoFile()throws Exception {mvc.perform(get("/api/partners/export").param("columns","ptnr_nm","ptnr_nm")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("columns"));unchanged();}
    @Test void outOfFilterSelectionRejectedBeforeAudit()throws Exception {when(mapper.exportRows(any())).thenReturn(List.of(exportRow(1)));mvc.perform(get("/api/partners/export").param("columns","ptnr_nm").param("ids","2")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("ids"));unchanged();}
    @Test void explicitEmptySelectionRejected()throws Exception {when(mapper.exportRows(any())).thenReturn(List.of(exportRow(1)));mvc.perform(get("/api/partners/export").param("columns","ptnr_nm").param("ids","")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("ids"));unchanged();}
    @Test void formulaLikeTextNeverBecomesFormula()throws Exception {var row=exportRow(1);row.put("ptnr_nm","=HYPERLINK(\"https://example.test\")");when(mapper.exportRows(any())).thenReturn(List.of(row));var result=mvc.perform(get("/api/partners/export").param("columns","ptnr_nm")).andExpect(status().isOk()).andReturn();try(var book=new XSSFWorkbook(new ByteArrayInputStream(result.getResponse().getContentAsByteArray()))){assertThat(book.getSheetAt(0).getRow(4).getCell(0).getCellType()).isEqualTo(CellType.STRING);}}
    @Test void mapperXmlIsPackagedAndAllInterfaceStatementsAreRegistered()throws Exception {
        var factory=new org.mybatis.spring.SqlSessionFactoryBean();factory.setDataSource(mock(javax.sql.DataSource.class));
        factory.setMapperLocations(new org.springframework.core.io.ClassPathResource("mappers/partners/PartnerActivityMapper.xml"));factory.afterPropertiesSet();
        var configuration=factory.getObject().getConfiguration();
        for(var method:PartnerActivityMapper.class.getDeclaredMethods()) assertThat(configuration.hasStatement(PartnerActivityMapper.class.getName()+"."+method.getName())).as(method.getName()).isTrue();
    }
}
