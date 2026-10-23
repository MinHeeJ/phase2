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
    @TestConfiguration static class Time { @Bean Clock clock(){return Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"),ZoneId.of("Asia/Seoul"));} }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean PartnerLifecycleMapper mapper;
    @BeforeEach void setup() {
        for(long id=1;id<=3;id++) {
            var state=new PartnerState(id,"거래처 "+id,"BOTH","Y");when(mapper.state(id)).thenReturn(state);when(mapper.lock(id)).thenReturn(state);
        }
        when(mapper.counts(any())).thenReturn(new PartnerCounts(0,0));
        when(mapper.detail(anyLong())).thenAnswer(a->Map.of("ptnr_id",a.getArgument(0).toString(),"ptnr_no","C-001","ptnr_nm","거래처","ptnr_se_cd","BOTH","biz_reg_no","123-45-67890","use_yn","Y"));
    }
    ResultActions disable(Object ids)throws Exception {return mvc.perform(post("/api/partners/disable").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("ids",ids))));}
    void noWrites() {verify(mapper,never()).status(any());verify(mapper,never()).appendHistory(any());verify(mapper,never()).appendAccess(any());}
    @ParameterizedTest @ValueSource(strings={"CUST","VEND"})
    @DisplayName("auth happy business side-effect: options retain requested kind and do not write")
    void optionsArePublicAndFlat(String kind)throws Exception {
        when(mapper.options(kind)).thenReturn(List.of(new PartnerOption("1","C-001","거래처")));
        mvc.perform(get("/api/partners/options").param("kind",kind)).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value("1")).andExpect(jsonPath("$.data[0].no").value("C-001")).andExpect(jsonPath("$.data[0].name").value("거래처"));noWrites();
    }
    @ParameterizedTest @ValueSource(strings={"BOTH","INVALID",""}) void invalidOptionKindHasField(String kind)throws Exception {mvc.perform(get("/api/partners/options").param("kind",kind)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("kind"));noWrites();}
    @Test @DisplayName("auth happy business side-effect: assignments retain affiliation and bind today's effective date without writes")
    void assignmentsPublicWithStringDateAndAffiliation()throws Exception {
        when(mapper.assignments(any())).thenReturn(List.of(new PartnerAssignment("프로젝트 A","가","협력사 B","BE","백엔드",BigDecimal.ONE,LocalDate.of(2026,10,31)),new PartnerAssignment("프로젝트 B","나","협력사 B","BE","백엔드",BigDecimal.ONE,LocalDate.of(2026,10,31))));
        mvc.perform(get("/api/partners/1/assignments")).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2)).andExpect(jsonPath("$.data[0].affiliation").value("협력사 B")).andExpect(jsonPath("$.data[0].endDate").value("2026-10-31"));
        verify(mapper).assignments(new PartnerTarget(1L,LocalDate.of(2026,10,22)));noWrites();
    }
    @Test @DisplayName("auth happy business side-effect: history masks legacy account values and never appends history")
    void historyPublicAndMasksLegacyAccountValues()throws Exception {
        when(mapper.history(1L)).thenReturn(List.of(new PartnerChange(LocalDateTime.of(2026,10,22,9,0),"acnt_no","계좌번호","123-4567-8901","987-6543-2109","UPDATE","기본 사용자")));
        mvc.perform(get("/api/partners/1/history")).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].before").value("8901")).andExpect(jsonPath("$.data[0].after").value("2109")).andExpect(jsonPath("$.data[0].actorName").value("기본 사용자"));noWrites();
    }
    @Test @DisplayName("validation: missing partner assignments are 404 before querying assignments")
    void absentAssignmentsAre404()throws Exception {
        mvc.perform(get("/api/partners/99/assignments")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        verify(mapper,never()).assignments(any());noWrites();
    }
    @Test @DisplayName("validation: missing partner history is 404 before querying history")
    void absentHistoryIs404()throws Exception {
        mvc.perform(get("/api/partners/99/history")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        verify(mapper,never()).history(anyLong());noWrites();
    }
    @Test @DisplayName("validation: missing enable target is 404 without INSERT or status mutation")
    void absentEnableIs404()throws Exception {
        mvc.perform(post("/api/partners/99/enable")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));noWrites();
    }
    @Test @DisplayName("auth happy business side-effect: distinct ids DISABLE once per partner")
    void disablesTwoPubliclyAndAuditsOncePerDistinctId()throws Exception {
        mvc.perform(post("/api/partners/disable").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("ids",List.of("2","1","2")))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.disabledIds.length()").value(2)).andExpect(jsonPath("$.data.blocked").isEmpty());
        verify(mapper).status(new PartnerStatusWrite(1L,"N",null));verify(mapper).status(new PartnerStatusWrite(2L,"N",null));
        verify(mapper).appendHistory(new PartnerHistoryWrite(1L,"DISABLE","Y","N",null));verify(mapper).appendHistory(new PartnerHistoryWrite(2L,"DISABLE","Y","N",null));
    }
    @Test void missingIdRejectsBeforeAnyWrites()throws Exception {disable(List.of("1","99")).andExpect(status().isNotFound());noWrites();}
    @Test void emptyIdsRejectsBeforeAnyWrites()throws Exception {disable(List.of()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("ids"));noWrites();}
    @ParameterizedTest @ValueSource(strings={"{\"ids\":[1]}","{\"ids\":[\"9223372036854775808\"]}","{\"ids\":[\"1\"],\"use_yn\":\"N\"}"}) void invalidBodyIs400(String body)throws Exception {mvc.perform(post("/api/partners/disable").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());noWrites();}
    @Test void partialBatchReturnsRealCountsAndOnlyAuditsSuccessfulRows()throws Exception {
        when(mapper.counts(new PartnerTarget(3L,LocalDate.of(2026,10,22)))).thenReturn(new PartnerCounts(0,3));
        disable(List.of("1","2","3")).andExpect(status().isOk()).andExpect(jsonPath("$.data.disabledIds.length()").value(2)).andExpect(jsonPath("$.data.blocked[0].peopleCount").value(3));
        verify(mapper,never()).status(new PartnerStatusWrite(3L,"N",null));verify(mapper,times(2)).appendHistory(any());
    }
    @ParameterizedTest @ValueSource(ints={0,3}) void singleBlockedIs409(int projects)throws Exception {
        when(mapper.counts(any())).thenReturn(new PartnerCounts(projects,projects==0?3:0));
        disable(List.of("1")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PARTNER_IN_USE"));noWrites();
    }
    @Test void allBlockedBatchIs200AndUnchanged()throws Exception {when(mapper.counts(any())).thenReturn(new PartnerCounts(1,0));disable(List.of("1","2")).andExpect(status().isOk()).andExpect(jsonPath("$.data.disabledIds").isEmpty()).andExpect(jsonPath("$.data.blocked.length()").value(2));noWrites();}
    @Test void repeatedDisableDoesNotAudit()throws Exception {when(mapper.lock(1L)).thenReturn(new PartnerState(1L,"거래처","VEND","N"));disable(List.of("1")).andExpect(status().isOk()).andExpect(jsonPath("$.data.disabledIds").isEmpty());noWrites();}
    @Test @DisplayName("auth happy business side-effect: enable preserves partner id and records N to Y data_chg_hist")
    void enablePubliclyPreservesIdentityAndAuditsNtoY()throws Exception {
        when(mapper.lock(1L)).thenReturn(new PartnerState(1L,"거래처","BOTH","N"));
        mvc.perform(post("/api/partners/1/enable")).andExpect(status().isOk()).andExpect(jsonPath("$.data.ptnr_id").value("1")).andExpect(jsonPath("$.data.ptnr_no").value("C-001")).andExpect(jsonPath("$.data.use_yn").value("Y"));
        verify(mapper).appendHistory(new PartnerHistoryWrite(1L,"UPDATE","N","Y",null));
    }
    @Test void alreadyEnabledDoesNotWrite()throws Exception {mvc.perform(post("/api/partners/1/enable")).andExpect(status().isOk());noWrites();}
    @Test void enableOmitsInapplicablePaymentKeys()throws Exception {
        when(mapper.lock(1L)).thenReturn(new PartnerState(1L,"고객","CUST","N"));when(mapper.detail(1L)).thenReturn(Map.of("ptnr_id","1","bank_cd","KB","acnt_no","12345678","dpstr_nm","고객","use_yn","Y"));
        mvc.perform(post("/api/partners/1/enable")).andExpect(status().isOk()).andExpect(jsonPath("$.data.acnt_no").doesNotExist()).andExpect(jsonPath("$.data.bank_cd").doesNotExist());
    }
    @Test @DisplayName("auth happy business side-effect: DOWNLOAD full account text cells and append access only")
    void exportIgnoresPageOrdersColumnsAndWritesRealXlsxWithAccess()throws Exception {
        var rows=new ArrayList<Map<String,Object>>();for(int i=0;i<120;i++)rows.add(Map.of("ptnr_nm","거래처 "+i,"biz_reg_no","123-45-67890","acnt_no","001-2345-6789","bank_cd","국민은행","dpstr_nm","예금주"));when(mapper.exportRows(any())).thenReturn(rows);
        byte[] bytes=mvc.perform(get("/api/partners/export").param("page","2").param("size","50").param("columns","biz_reg_no,ptnr_nm,paymentAccount"))
            .andExpect(status().isOk()).andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).andExpect(header().string("Content-Disposition",org.hamcrest.Matchers.startsWith("attachment; filename*=UTF-8''"))).andReturn().getResponse().getContentAsByteArray();
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var s=book.getSheetAt(0);assertThat(s.getLastRowNum()).isEqualTo(123);assertThat(s.getRow(3).getCell(0).getStringCellValue()).isEqualTo("사업자번호");assertThat(s.getRow(4).getCell(3).getStringCellValue()).isEqualTo("001-2345-6789");assertThat(s.getRow(4).getCell(3).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.STRING);assertThat(s.getPaneInformation().getHorizontalSplitPosition()).isEqualTo((short)4);
        }
        verify(mapper).appendAccess(argThat(w->w.filename().equals("거래처_20261022.xlsx")&&w.actor()==null&&w.detail().contains("\"rowCount\":120")));
        verify(mapper,never()).status(any());verify(mapper,never()).appendHistory(any());
    }
    @Test void exportAcceptsTheListDefaultKindAndTrimsTheSameSearch()throws Exception {
        when(mapper.exportRows(any())).thenReturn(List.of());
        mvc.perform(get("/api/partners/export").param("columns","ptnr_nm").param("kind","").param("useYn","Y").param("q"," 1234567890 "))
            .andExpect(status().isOk());
        verify(mapper).exportRows(argThat(q->q.kind().isEmpty() && q.useYn().equals("Y") && q.q().equals("1234567890")));
    }
    @Test void selectedExportPassesIdsWithoutPageLimit()throws Exception {
        when(mapper.exportRows(any())).thenReturn(List.of(Map.of("ptnr_nm","하나"),Map.of("ptnr_nm","둘")));
        byte[] bytes=mvc.perform(get("/api/partners/export").param("columns","ptnr_nm").param("ids","1,2").param("page","2")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(bytes))){assertThat(book.getSheetAt(0).getLastRowNum()).isEqualTo(5);}
        verify(mapper).exportRows(argThat(q->q.ids().equals(List.of(1L,2L))));
    }
    @ParameterizedTest @ValueSource(strings={"","unknown","ptnr_nm,ptnr_nm","bank_cd"}) void invalidColumnsDoNotCreateFileOrAccess(String columns)throws Exception {mvc.perform(get("/api/partners/export").param("columns",columns)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("columns"));noWrites();}
    @Test void missingColumnsIs400()throws Exception {mvc.perform(get("/api/partners/export")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("columns"));noWrites();}
    @Test void unknownExportIdDoesNotReadOrLog()throws Exception {mvc.perform(get("/api/partners/export").param("columns","ptnr_nm").param("ids","99")).andExpect(status().isNotFound());verify(mapper,never()).exportRows(any());noWrites();}
    @Test void invalidSortIs400()throws Exception {mvc.perform(get("/api/partners/export").param("columns","ptnr_nm").param("sort","ptnr_nm;DROP,asc")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("sort"));noWrites();}
    @Test void paymentColumnsInheritSensitiveAnnotation() {for(String key:List.of("bank_cd","acnt_no","dpstr_nm"))assertThat(ExcelExportService.Column.marked(key,key,PartnerPayment.class).sensitive()).isTrue();}
}
