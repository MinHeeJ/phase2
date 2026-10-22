package com.sicost.admin;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sicost.admin.AdminModels.*;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminHttpTest {
    private AdminMapper mapper;
    private MockMvc mvc;
    private ObjectMapper json;
    private final CodeRow be=new CodeRow("BE","백엔드",1,"Y","100",null,null,null);
    private final CodeGroup job=new CodeGroup("JOB","직무","BIZ","기본 단가(원)",null,null,1,"Y",1);
    private final ExcelMap excel=new ExcelMap("1","개발","JOB","BE","직무","백엔드","MAIN","이전","Y");
    private final OperatingParam vat=new OperatingParam("VAT_RATE","2000-01","부가세",new BigDecimal("10"),null,"%","세금");
    @BeforeEach
    void configureHttp() {
        mapper=mock(AdminMapper.class);
        json=new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var validator=new LocalValidatorFactoryBean(); validator.afterPropertiesSet();
        var service=new AdminService(mapper,new CurrentUser(),new PermissionService(),json,Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"),ZoneId.of("Asia/Seoul")));
        mvc=MockMvcBuilders.standaloneSetup(new AdminController(service,new CurrentUser(),json)).setControllerAdvice(new ApiExceptionHandler()).setValidator(validator).addFilters(new RequestIdFilter()).build();
        when(mapper.group("JOB")).thenReturn(job); when(mapper.codes(any())).thenReturn(List.of(be)); when(mapper.codeCount("JOB")).thenReturn(1L);
        when(mapper.groups(any())).thenReturn(List.of(job)); when(mapper.groupCount()).thenReturn(1L);
        when(mapper.excelMap(1)).thenReturn(excel); when(mapper.excelMaps(any())).thenReturn(List.of(excel)); when(mapper.excelCount()).thenReturn(1L);
        when(mapper.source(eq("VAT_RATE"),anyString())).thenReturn(vat); when(mapper.versionCount("VAT_RATE")).thenReturn(1L); when(mapper.versions(any())).thenReturn(List.of(vat)); when(mapper.params(any())).thenReturn(List.of(vat)); when(mapper.paramCount(anyString())).thenReturn(1L);
        when(mapper.activeCode("JOB","BE")).thenReturn(1); when(mapper.lastClosed()).thenReturn("2026-09");
        doAnswer(invocation->{ invocation.<ExcelInsert>getArgument(0).setMapId(7L); return 1; }).when(mapper).insertExcel(any());
    }
    private void write(String method,String path,Object body,int status) throws Exception {
        mvc.perform(request(org.springframework.http.HttpMethod.valueOf(method),path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body))).andExpect(status().is(status));
    }
    @Test void fixtureIsClasspathAndAllReadOperationsArePublicSingleEnvelope() throws Exception {
        assertThat(new ClassPathResource("contracts/openapi.yaml").exists()).isTrue();
        for(String path:List.of("/code-groups","/code-groups/JOB/codes","/excel-maps","/params","/params/VAT_RATE/versions")) mvc.perform(get("/api/admin"+path)).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.items").isArray()).andExpect(jsonPath("$.data.success").doesNotExist()).andExpect(jsonPath("$.data.page").value(1)).andExpect(jsonPath("$.data.size").value(50));
    }
    @Test void filteredHistoryUsesSameGroupForListAndCount() throws Exception {
        when(mapper.history(any())).thenReturn(List.of(new CodeChange(LocalDateTime.of(2026,10,22,9,0),"PARAM","기준값","VAT_RATE","UPDATE","적용 시작월","2000-01","2026-11",null)));
        when(mapper.historyCount(any())).thenReturn(1L);
        mvc.perform(get("/api/admin/code-history").param("group","PARAM")).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.items[0].cd_grp_nm").value("기준값"));
        var capture=org.mockito.ArgumentCaptor.forClass(AdminListQuery.class); verify(mapper).history(capture.capture()); verify(mapper).historyCount(capture.getValue()); assertThat(capture.getValue().group()).isEqualTo("PARAM");
    }
    @ParameterizedTest @ValueSource(strings={"page=0","size=30","sort=sort_seq;DROP,asc","sort=sort_seq,ASC","sort=unknown,asc"})
    void invalidPageAndUnsafeSortRejected(String query) throws Exception { String[] part=query.split("="); mvc.perform(get("/api/admin/code-groups").param(part[0],part[1])).andExpect(status().isBadRequest()); verify(mapper,never()).groups(any()); }
    @Test void existingMissingGroupIs404() throws Exception { mvc.perform(get("/api/admin/code-groups/MISSING/codes")).andExpect(status().isNotFound()); }
    @Test void absentVersionKeyIs404() throws Exception { mvc.perform(get("/api/admin/params/MISSING/versions")).andExpect(status().isNotFound()); }
    @Test void futureVersionsNotCurrentMonthLimited() throws Exception { mvc.perform(get("/api/admin/params/VAT_RATE/versions")).andExpect(status().isOk()); verify(mapper).versions(argThat(q->q.month()==null && q.orderBy().startsWith("aply_bgn_ym DESC"))); }
    @Test void currentMonthIsSeoul() throws Exception { mvc.perform(get("/api/admin/params")).andExpect(status().isOk()); verify(mapper).params(argThat(q->q.month().equals("2026-10"))); }
    @Test void createGroupPublicAddsBizAndNullDetailHistory() throws Exception {
        write("POST","/api/admin/code-groups",Map.of("cd_grp_id","NEW_GROUP","cd_grp_nm","새 그룹"),200);
        verify(mapper).insertGroup(argThat(w->w.row().cd_grp_id().equals("NEW_GROUP") && w.userId()==null)); verify(mapper).insertHistory(argThat(h->h.type().equals("ADD") && h.code()==null && h.userId()==null));
    }
    @ParameterizedTest @CsvSource({"A1,이름,cd_grp_id","abc,이름,cd_grp_id","ABC,'',cd_grp_nm"})
    void groupValidationBeforeMutation(String key,String name,String field) throws Exception {
        mvc.perform(post("/api/admin/code-groups").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("cd_grp_id",key,"cd_grp_nm",name)))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field)); verify(mapper,never()).insertGroup(any()); verify(mapper,never()).insertHistory(any());
    }
    @Test void groupAttributeLengthRejected() throws Exception { write("POST","/api/admin/code-groups",Map.of("cd_grp_id","ABC","cd_grp_nm","이름","attr1_nm","가".repeat(51)),400); }
    @Test void duplicateGroup409() throws Exception { write("POST","/api/admin/code-groups",Map.of("cd_grp_id","JOB","cd_grp_nm","이름"),409); verify(mapper,never()).insertGroup(any()); }
    @Test void newExcelRequiresExplicitRoleAndRecordsGeneratedId() throws Exception {
        write("POST","/api/admin/excel-maps",Map.of("excel_lbl_nm","테스트표기","trns_cd_grp_id","JOB","trns_cd","BE","map_role_cd","MAIN"),200);
        verify(mapper).insertHistory(argThat(h->h.type().equals("ADD") && h.item().equals("7") && h.after().contains("\"map_id\":\"7\"") && h.userId()==null));
    }
    @ParameterizedTest @CsvSource({"excel_lbl_nm,엑셀 글자를 입력하세요.","trns_cd_grp_id,대응할 코드를 고르세요.","trns_cd,대응할 코드를 고르세요."})
    void excelRequiredFields(String field,String message) throws Exception {
        Map<String,Object> body=new HashMap<>(Map.of("excel_lbl_nm","표기","trns_cd_grp_id","JOB","trns_cd","BE","map_role_cd","MAIN")); body.put(field,"");
        mvc.perform(post("/api/admin/excel-maps").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field)).andExpect(jsonPath("$.message").value(message)); verify(mapper,never()).insertExcel(any());
    }
    @Test void inactiveTargetRejected() throws Exception { when(mapper.activeCode("JOB","BE")).thenReturn(0); write("POST","/api/admin/excel-maps",Map.of("excel_lbl_nm","표기","trns_cd_grp_id","JOB","trns_cd","BE","map_role_cd","MAIN"),400); }
    @Test void duplicateTriple409HasSpecificMessage() throws Exception {
        when(mapper.excelDuplicate(any())).thenReturn(1L);
        mvc.perform(post("/api/admin/excel-maps").contentType(MediaType.APPLICATION_JSON).content("{\"excel_lbl_nm\":\"표기\",\"trns_cd_grp_id\":\"JOB\",\"trns_cd\":\"BE\",\"map_role_cd\":\"MAIN\"}")).andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("이미 있는 대응입니다.")); verify(mapper,never()).insertExcel(any());
    }
    @Test void patchPublicAllowedFieldsOnlyAndItemHistory() throws Exception {
        write("PATCH","/api/admin/excel-maps/1",Map.of("map_role_cd","SUB","rmrk_desc","수정"),200);
        verify(mapper).patchExcel(argThat(w->w.changes().equals(Map.of("map_role_cd","SUB","rmrk_desc","수정"))));
        verify(mapper).insertHistory(argThat(h->h.item().equals("rmrk_desc") && h.before().contains("이전") && h.after().contains("수정")));
    }
    @Test void softDisableLeavesRow() throws Exception { write("PATCH","/api/admin/excel-maps/1",Map.of("use_yn","N"),200); verify(mapper).patchExcel(argThat(w->w.changes().equals(Map.of("use_yn","N")))); }
    @ParameterizedTest @ValueSource(strings={"excel_lbl_nm","trns_cd_grp_id","trns_cd","map_id"})
    void patchImmutableFieldsAre400(String field) throws Exception { mvc.perform(patch("/api/admin/excel-maps/1").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(field,"change","map_role_cd","SUB")))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field)); verify(mapper,never()).patchExcel(any()); verify(mapper,never()).insertHistory(any()); }
    @Test void longMemoHasFieldAndNoWrites() throws Exception { mvc.perform(patch("/api/admin/excel-maps/1").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("rmrk_desc","가".repeat(201))))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("rmrk_desc")); verify(mapper,never()).insertHistory(any()); }
    @ParameterizedTest @ValueSource(strings={"{}","{\"rmrk_desc\":null}","{\"use_yn\":null}","{\"map_role_cd\":\"OTHER\"}"})
    void patchEmptyNullAndInvalidEnumsRejected(String body) throws Exception { mvc.perform(patch("/api/admin/excel-maps/1").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest()); }
    @Test void omittedMemoKeptAndEmptyMemoClears() throws Exception { write("PATCH","/api/admin/excel-maps/1",Map.of("rmrk_desc",""),200); verify(mapper).patchExcel(argThat(w->w.changes().get("rmrk_desc").equals(""))); }
    @Test void unknownMapIs404() throws Exception { write("PATCH","/api/admin/excel-maps/999",Map.of("rmrk_desc","새 메모"),404); }
    private Map<String,Object> code(String code,String name,String use) { return Map.of("dtl_cd",code,"cd_nm",name,"sort_seq",1,"use_yn",use,"attr1_val","100"); }
    @Test void fullSaveCreatesAndUpdatesWithNoAuth() throws Exception {
        write("PUT","/api/admin/code-groups/JOB/codes",List.of(code("BE","변경","Y"),code("TEST1","테스트","Y")),200); verify(mapper).insertCode(any()); verify(mapper).updateCode(any()); verify(mapper).insertHistory(argThat(h->h.type().equals("ADD") && h.code().equals("TEST1")));
    }
    @Test void missingExistingCodeRejected() throws Exception { write("PUT","/api/admin/code-groups/JOB/codes",List.of(code("TEST1","새 코드","Y")),400); verify(mapper,never()).insertCode(any()); verify(mapper,never()).insertHistory(any()); }
    @Test void duplicateRejectsOtherValidChangeBeforeDml() throws Exception { write("PUT","/api/admin/code-groups/JOB/codes",List.of(code("BE","변경","Y"),code("BE","중복","Y")),409); verify(mapper,never()).updateCode(any()); verify(mapper,never()).insertHistory(any()); }
    @ParameterizedTest @CsvSource({"'',이름,[0].dtl_cd,코드를 입력하세요.","BE,'',[0].cd_nm,이름을 입력하세요."})
    void codeRequiredErrorsAreIndexed(String key,String name,String field,String message) throws Exception { mvc.perform(put("/api/admin/code-groups/JOB/codes").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(List.of(code(key,name,"Y"))))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field)).andExpect(jsonPath("$.message").value(message)); }
    @Test void systemUseHasPriorityAndNoPartialRename() throws Exception { when(mapper.group("JOB")).thenReturn(new CodeGroup("JOB","직무","SYSTEM",null,null,null,1,"Y",1)); mvc.perform(put("/api/admin/code-groups/JOB/codes").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(List.of(code("BE","이름 변경","N"))))).andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("시스템 코드는 사용 중지할 수 없습니다.")); verify(mapper,never()).insertHistory(any()); }
    @Test void systemNameAndOrderAllowed() throws Exception { when(mapper.group("JOB")).thenReturn(new CodeGroup("JOB","직무","SYSTEM",null,null,null,1,"Y",1)); write("PUT","/api/admin/code-groups/JOB/codes",List.of(code("BE","새 이름","Y")),200); }
    @Test void systemAttributeLocked() throws Exception { when(mapper.group("JOB")).thenReturn(new CodeGroup("JOB","직무","SYSTEM",null,null,null,1,"Y",1)); var row=new HashMap<>(code("BE","변경","Y")); row.put("attr1_val","200"); write("PUT","/api/admin/code-groups/JOB/codes",List.of(row),409); }
    @Test void mixedStatusAndNameProduceBothHistoryRows() throws Exception { write("PUT","/api/admin/code-groups/JOB/codes",List.of(code("BE","변경","N")),200); verify(mapper).insertHistory(argThat(h->h.item().equals("cd_nm") && h.type().equals("UPDATE"))); verify(mapper).insertHistory(argThat(h->h.item().equals("use_yn") && h.type().equals("DISABLE"))); }
    @Test void sameValueCreatesNoHistory() throws Exception { write("PUT","/api/admin/code-groups/JOB/codes",List.of(code("BE","백엔드","Y")),200); verify(mapper,never()).insertHistory(any()); verify(mapper,never()).updateCode(any()); }
    @Test void paramPublicUpsertUsesPathMonthAndSource() throws Exception { write("PUT","/api/admin/params/VAT_RATE/versions/2026-11",Map.of("num_val",11),200); verify(mapper).saveParam(argThat(w->w.month().equals("2026-11") && w.sourceMonth().equals("2000-01") && w.num().compareTo(new BigDecimal("11"))==0)); verify(mapper).insertHistory(argThat(h->h.group().equals("PARAM") && h.item().equals("적용 시작월"))); }
    @ParameterizedTest @ValueSource(strings={"-1","101","0.00001","100000000000000"})
    void invalidNumericParamNoMutation(String value) throws Exception { mvc.perform(put("/api/admin/params/VAT_RATE/versions/2026-11").contentType(MediaType.APPLICATION_JSON).content("{\"num_val\":"+value+"}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("num_val")).andExpect(jsonPath("$.message").value("올바른 값을 입력하세요.")); verify(mapper,never()).saveParam(any()); verify(mapper,never()).insertHistory(any()); }
    @ParameterizedTest @ValueSource(ints={0,100}) void percentBoundariesAllowed(int value) throws Exception { write("PUT","/api/admin/params/VAT_RATE/versions/2026-11",Map.of("num_val",value),200); }
    @Test void closedMonthRejected() throws Exception { write("PUT","/api/admin/params/VAT_RATE/versions/2026-09",Map.of("num_val",11),409); verify(mapper,never()).saveParam(any()); verify(mapper,never()).insertHistory(any()); }
    @ParameterizedTest @ValueSource(strings={"{}","{\"num_val\":null}","{\"num_val\":11,\"txt_val\":\"x\"}","{\"txt_val\":\"x\"}"})
    void paramTypeMismatchRejected(String body) throws Exception { mvc.perform(put("/api/admin/params/VAT_RATE/versions/2026-11").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest()); }
    @ParameterizedTest @ValueSource(strings={"2026-13","0000-01","2026-1"}) void strictMonth(String month) throws Exception { write("PUT","/api/admin/params/VAT_RATE/versions/"+month,Map.of("num_val",11),400); }
    @Test void missingParam404() throws Exception { write("PUT","/api/admin/params/MISSING/versions/2026-11",Map.of("num_val",11),404); }
    @ParameterizedTest @ValueSource(ints={0,201}) void invalidTextRejected(int length) throws Exception { when(mapper.source(eq("TEXT"),anyString())).thenReturn(new OperatingParam("TEXT","2000-01","문자",null,"기존",null,null)); write("PUT","/api/admin/params/TEXT/versions/2026-11",Map.of("txt_val","가".repeat(length)),400); verify(mapper,never()).saveParam(any()); }
    @Test void malformedJsonAndSqlFailuresAreSafe() throws Exception { mvc.perform(post("/api/admin/code-groups").contentType(MediaType.APPLICATION_JSON).content("{")).andExpect(status().isBadRequest()); when(mapper.groupCount()).thenThrow(new IllegalStateException("SELECT secret")); var response=mvc.perform(get("/api/admin/code-groups")).andExpect(status().isInternalServerError()).andReturn().getResponse(); assertThat(response.getContentAsString()).doesNotContain("SELECT","secret"); }
}
