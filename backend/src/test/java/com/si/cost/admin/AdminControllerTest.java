package com.si.cost.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.si.cost.common.api.*;
import com.si.cost.common.user.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static com.si.cost.admin.AdminModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminController.class)
@Import({AdminService.class, PermissionService.class, RequestIdFilter.class, ApiExceptionHandler.class, AdminControllerTest.TimeConfiguration.class})
class AdminControllerTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean AdminMapper mapper;
    final List<Audit> audits = new ArrayList<>();
    final Map<String,List<Code>> codes = new HashMap<>();
    final Map<Long,ExcelMap> maps = new LinkedHashMap<>();
    final Map<String,Param> params = new LinkedHashMap<>();
    @TestConfiguration static class TimeConfiguration {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"),ZoneId.of("Asia/Seoul")); }
    }
    Group group(String key,String kind) { return new Group(key,key,kind,"기본 단가(원)",null,null,1,"Y",2); }
    Code code(String key,String name,String use,String attr) { return new Code(key,name,1,use,attr,null,null,null); }
    @BeforeEach void setup() {
        audits.clear(); codes.clear(); maps.clear(); params.clear();
        codes.put("JOB",new ArrayList<>(List.of(code("BE","백엔드","Y",null),code("OPS","운영","Y",null))));
        codes.put("POSITION",new ArrayList<>(List.of(code("TL","팀장","Y","200000"))));
        codes.put("CONTRACT_TYPE",new ArrayList<>(List.of(code("TK","턴키","Y",null))));
        when(mapper.group(anyString())).thenAnswer(a -> codes.containsKey(a.getArgument(0)) ? group(a.getArgument(0),a.getArgument(0).equals("CONTRACT_TYPE") ? "SYSTEM" : "BIZ") : null);
        when(mapper.allCodes(anyString())).thenAnswer(a -> List.copyOf(codes.get(a.getArgument(0))));
        when(mapper.codes(anyString(),any(),eq(DataScope.ALL))).thenAnswer(a -> List.copyOf(codes.get(a.getArgument(0))));
        when(mapper.codeCount(anyString())).thenAnswer(a -> (long)codes.get(a.getArgument(0)).size());
        when(mapper.insertCode(anyString(),any())).thenAnswer(a -> { codes.get(a.getArgument(0)).add(a.getArgument(1)); return 1; });
        when(mapper.updateCode(anyString(),anyString(),anyMap())).thenAnswer(a -> {
            List<Code> rows=codes.get(a.getArgument(0)); String key=a.getArgument(1); Map<String,Object> patch=a.getArgument(2);
            for(int i=0;i<rows.size();i++) { Code old=rows.get(i); if(old.code().equals(key)) rows.set(i,new Code(old.code(),(String)patch.getOrDefault("cd_nm",old.name()),(Integer)patch.getOrDefault("sort_seq",old.order()),(String)patch.getOrDefault("use_yn",old.use()),(String)patch.getOrDefault("attr1_val",old.attr1()),(String)patch.getOrDefault("attr2_val",old.attr2()),(String)patch.getOrDefault("attr3_val",old.attr3()),(LocalDate)patch.getOrDefault("aply_bgn_dt",old.date()))); }
            return 1;
        });
        when(mapper.audit(any())).thenAnswer(a -> { audits.add(a.getArgument(0)); return 1; });
        maps.put(1L,new ExcelMap("1","기존 표기","JOB","BE","직무","백엔드","MAIN","이전 메모","Y"));
        when(mapper.excelMap(anyLong())).thenAnswer(a -> maps.get(a.getArgument(0)));
        when(mapper.maps(any(),eq(DataScope.ALL))).thenAnswer(a -> List.copyOf(maps.values()));
        when(mapper.mapCount()).thenAnswer(a -> (long)maps.size());
        when(mapper.activeTarget(anyString(),anyString())).thenReturn(true);
        when(mapper.insertMap(any())).thenAnswer(a -> { MapCreate r=a.getArgument(0); long id=maps.size()+1; maps.put(id,new ExcelMap(Long.toString(id),r.label(),r.groupId(),r.code(),"직무","백엔드",r.role(),r.memo(),"Y")); return id; });
        when(mapper.updateMap(anyLong(),anyMap())).thenAnswer(a -> {
            long id=a.getArgument(0); ExcelMap r=maps.get(id); Map<String,Object> c=a.getArgument(1);
            maps.put(id,new ExcelMap(r.id(),r.label(),r.groupId(),r.code(),r.groupName(),r.codeName(),(String)c.getOrDefault("map_role_cd",r.role()),(String)c.getOrDefault("rmrk_desc",r.memo()),(String)c.getOrDefault("use_yn",r.use()))); return 1;
        });
        when(mapper.lastClosed()).thenReturn("2026-09");
        when(mapper.paramSource(anyString())).thenAnswer(a -> {
            String key=a.getArgument(0); if (key.equals("UNKNOWN")) return null;
            boolean text=key.equals("AR_AGING_DAYS"); return new ParamSource(key,"2000-01",key,text?null:BigDecimal.TEN,text?"30,60,90":null,"%","사용처","설명");
        });
        when(mapper.paramBefore(anyString(),anyString())).thenAnswer(a -> params.getOrDefault(a.getArgument(0)+":"+a.getArgument(1),new Param(a.getArgument(0),"2000-01","부가세율",BigDecimal.TEN,null,"%","사용처")));
        when(mapper.saveParam(any(),anyString(),any())).thenAnswer(a -> {
            ParamSource s=a.getArgument(0); String month=a.getArgument(1); ParamSave v=a.getArgument(2);
            params.put(s.key()+":"+month,new Param(s.key(),month,s.name(),v.number(),v.text(),s.unit(),s.usage())); return 1;
        });
        when(mapper.versions(anyString(),any(),eq(DataScope.ALL))).thenAnswer(a -> params.values().stream().filter(p -> p.key().equals(a.getArgument(0))).toList());
        when(mapper.versionCount(anyString())).thenAnswer(a -> params.values().stream().filter(p -> p.key().equals(a.getArgument(0))).count());
        when(mapper.history(any(),any(),eq(DataScope.ALL))).thenAnswer(a -> audits.stream().filter(e -> a.getArgument(0)==null || e.groupId().equals(a.getArgument(0))).map(e -> new Change(LocalDateTime.of(2026,10,1,9,0),e.groupId(),e.groupId(),e.code(),e.type(),e.item(),e.before(),e.after(),null)).toList());
        when(mapper.historyCount(any())).thenAnswer(a -> audits.stream().filter(e -> a.getArgument(0)==null || e.groupId().equals(a.getArgument(0))).count());
    }
    void putCodes(String group,List<Code> rows,int status) throws Exception { mvc.perform(put("/api/admin/code-groups/"+group+"/codes").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(rows))).andExpect(status().is(status)); }
    void saveParam(String key,String month,String body,int status) throws Exception { mvc.perform(put("/api/admin/params/"+key+"/versions/"+month).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().is(status)).andExpect(jsonPath("$.success").value(status==200)); }
    @Test void fixtureComesFromClasspath() throws Exception {
        try(var input=new ClassPathResource("contracts/openapi.yaml").getInputStream()) { assertThat(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).contains("operationId: saveGroupCodes","operationId: updateExcelMap","operationId: saveParamVersion"); }
    }
    @Test void groupsReadIsPublicAndNestedWithServerTotal() throws Exception {
        when(mapper.groups(any(),eq(DataScope.ALL))).thenReturn(List.of(group("CONTRACT_TYPE","SYSTEM"),group("JOB","BIZ"))); when(mapper.groupCount()).thenReturn(36L);
        mvc.perform(get("/api/admin/code-groups")).andExpect(status().isOk()).andExpect(header().exists("X-Request-Id"))
            .andExpect(jsonPath("$.data.success").value(true)).andExpect(jsonPath("$.data.data.total").value(36)).andExpect(jsonPath("$.data.data.page").value(0)).andExpect(jsonPath("$.data.data.size").value(50))
            .andExpect(jsonPath("$.data.data.items[0].cd_grp_se_cd").value("SYSTEM")).andExpect(jsonPath("$.data.data.items[1].codeCount").value(2));
    }
    @ParameterizedTest @ValueSource(strings={"/api/admin/code-groups","/api/admin/code-groups/JOB/codes","/api/admin/excel-maps","/api/admin/params","/api/admin/params/VAT_RATE/versions","/api/admin/code-history"})
    void invalidPaginationIs400(String path) throws Exception {
        mvc.perform(get(path).param("size","21")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("size"));
        mvc.perform(get(path).param("page","-1")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("page"));
        mvc.perform(get(path).param("sort","name;drop,asc")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("sort"));
    }
    @Test void managedReadRetainsInactiveNameAndNullableAttributes() throws Exception {
        codes.get("JOB").set(1,code("OPS","운영","N",null));
        mvc.perform(get("/api/admin/code-groups/JOB/codes")).andExpect(status().isOk()).andExpect(jsonPath("$.data.data.items[1].cd_nm").value("운영")).andExpect(jsonPath("$.data.data.items[1].use_yn").value("N"));
    }
    @Test void unknownManagedGroup404() throws Exception { mvc.perform(get("/api/admin/code-groups/UNKNOWN/codes")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND")); }
    @Test void currentParamsUseSeoulMonthNotFutureMetadata() throws Exception {
        when(mapper.currentParams(eq("2026-10"),any(),eq(DataScope.ALL))).thenReturn(List.of(new Param("VAT_RATE","2000-01","부가세率",BigDecimal.TEN,null,"%","사용처"))); when(mapper.currentParamCount("2026-10")).thenReturn(1L);
        mvc.perform(get("/api/admin/params")).andExpect(status().isOk()).andExpect(jsonPath("$.data.data.items[0].num_val").value(10)).andExpect(jsonPath("$.data.data.items[0].txt_val").value(nullValue()));
        verify(mapper).currentParamCount("2026-10");
    }
    @Test void historyFilterAndCountShareGroupAndDefaultDescending50() throws Exception {
        mapper.audit(new Audit("JOB","BE","UPDATE","cd_nm","이전","이후")); mapper.audit(new Audit("POSITION","TL","UPDATE","attr1_val","200000","250000"));
        mvc.perform(get("/api/admin/code-history").param("group","JOB")).andExpect(status().isOk()).andExpect(jsonPath("$.data.data.total").value(1)).andExpect(jsonPath("$.data.data.items",hasSize(1))).andExpect(jsonPath("$.data.data.items[0].chg_user_id").value(nullValue()));
        verify(mapper).history(eq("JOB"),eq(new PageRequest(0,50,"chg_at",true)),eq(DataScope.ALL));
    }
    @Test void groupCreationHasAddNullCodeAndActorAndSingleSuccessEnvelope() throws Exception {
        mvc.perform(post("/api/admin/code-groups").contentType(MediaType.APPLICATION_JSON).content("{\"cd_grp_id\":\"TEST_GROUP\",\"cd_grp_nm\":\"테스트\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data").doesNotExist());
        verify(mapper).insertGroup(new GroupCreate("TEST_GROUP","테스트",null,null,null));
        assertThat(audits).containsExactly(new Audit("TEST_GROUP",null,"ADD","cd_grp_id",null,"TEST_GROUP"));
        mvc.perform(get("/api/admin/code-history").param("group","TEST_GROUP")).andExpect(status().isOk()).andExpect(jsonPath("$.data.data.items[0].dtl_cd").value(nullValue()));
    }
    @ParameterizedTest @CsvSource({"cd_grp_id,A1","cd_grp_id,''","cd_grp_nm,''"})
    void invalidGroupNeverWrites(String field,String value) throws Exception {
        Map<String,String> body=new HashMap<>(Map.of("cd_grp_id","TEST_GROUP","cd_grp_nm","이름")); body.put(field,value);
        mvc.perform(post("/api/admin/code-groups").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field));
        verify(mapper,never()).insertGroup(any()); assertThat(audits).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"attr1_nm","attr2_nm","attr3_nm"}) void groupAttributeLengths(String field) throws Exception {
        mvc.perform(post("/api/admin/code-groups").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of("cd_grp_id","TEST_GROUP","cd_grp_nm","이름",field,"가".repeat(51)))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field)); verify(mapper,never()).insertGroup(any());
    }
    @Test void fullSaveCreatesNewCodeWithAdd() throws Exception {
        List<Code> body=new ArrayList<>(codes.get("JOB")); body.add(new Code("TEST1","테스트",3,"Y",null,null,null,null)); putCodes("JOB",body,200);
        mvc.perform(get("/api/admin/code-groups/JOB/codes")).andExpect(status().isOk()).andExpect(jsonPath("$.data.data.items[2].dtl_cd").value("TEST1"));
        assertThat(audits).containsExactly(new Audit("JOB","TEST1","ADD","dtl_cd",null,"TEST1"));
    }
    @Test void existingCodeUpdateWorksWithoutAuthenticationAndAuditsScalar() throws Exception {
        putCodes("POSITION",List.of(code("TL","팀장","Y","250000")),200);
        assertThat(audits).containsExactly(new Audit("POSITION","TL","UPDATE","attr1_val","200000","250000"));
        mvc.perform(get("/api/admin/code-groups/POSITION/codes")).andExpect(status().isOk()).andExpect(jsonPath("$.data.data.items[0].attr1_val").value("250000"));
    }
    @Test void omittedPersistedCodeRejectsEverything() throws Exception { putCodes("JOB",List.of(code("OPS","다른 이름","Y",null)),400); assertThat(codes.get("JOB").get(1).name()).isEqualTo("운영"); assertThat(audits).isEmpty(); verify(mapper,never()).updateCode(any(),any(),any()); }
    @Test void systemAllowsNameAndOrderOnly() throws Exception { putCodes("CONTRACT_TYPE",List.of(new Code("TK","턴키(일괄)",2,"Y",null,null,null,null)),200); assertThat(codes.get("CONTRACT_TYPE").get(0).name()).isEqualTo("턴키(일괄)"); }
    @Test void systemUseChangeRejectsNameAndAuditToo() throws Exception {
        mvc.perform(put("/api/admin/code-groups/CONTRACT_TYPE/codes").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(List.of(code("TK","바뀐 이름","N",null)))))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SYSTEM_CODE_LOCKED")).andExpect(jsonPath("$.message").value("시스템 코드는 사용 중지할 수 없습니다."));
        assertThat(codes.get("CONTRACT_TYPE").get(0).name()).isEqualTo("턴키"); assertThat(audits).isEmpty();
    }
    @Test void systemAttributeChangeIsAtomicConflict() throws Exception { putCodes("CONTRACT_TYPE",List.of(code("TK","바뀐 이름","Y","1")),409); assertThat(audits).isEmpty(); }
    @Test void systemNewCodeIsConflict() throws Exception { putCodes("CONTRACT_TYPE",List.of(code("TK","턴키","Y",null),code("NEW","신규","Y",null)),409); assertThat(audits).isEmpty(); }
    @Test void duplicateRowsRejectOtherwiseValidChanges() throws Exception {
        putCodes("JOB",List.of(code("BE","다른 이름","Y",null),code("BE","중복","Y",null),code("OPS","운영","Y",null)),409);
        assertThat(codes.get("JOB").get(0).name()).isEqualTo("백엔드"); assertThat(audits).isEmpty();
    }
    @ParameterizedTest @CsvSource({"dtl_cd,코드를 입력하세요.","cd_nm,이름을 입력하세요."}) void requiredCodeRowHasIndexedError(String field,String message) throws Exception {
        Map<String,Object> row=new HashMap<>(Map.of("dtl_cd","BE","cd_nm","백엔드","sort_seq",1,"use_yn","Y")); row.put(field,"");
        mvc.perform(put("/api/admin/code-groups/JOB/codes").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(List.of(row))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("[0]."+field)).andExpect(jsonPath("$.message").value(message)); assertThat(audits).isEmpty();
    }
    @Test void disableAndEnableKeepManagedRowAndSeparateHistories() throws Exception {
        putCodes("JOB",List.of(code("BE","백엔드","Y",null),code("OPS","운영","N",null)),200);
        assertThat(codes.get("JOB")).hasSize(2); assertThat(codes.get("JOB").get(1).use()).isEqualTo("N");
        putCodes("JOB",List.of(code("BE","백엔드","Y",null),code("OPS","운영","Y",null)),200);
        assertThat(audits).extracting(Audit::type).containsExactly("DISABLE","ENABLE");
    }
    @Test void lastSequentialSaveWinsAndNoOpDoesNotAudit() throws Exception {
        putCodes("POSITION",List.of(code("TL","첫 값","Y","200000")),200); putCodes("POSITION",List.of(code("TL","나중 값","Y","200000")),200);
        putCodes("POSITION",List.of(code("TL","나중 값","Y","200000")),200);
        assertThat(codes.get("POSITION").get(0).name()).isEqualTo("나중 값"); assertThat(audits).hasSize(2);
    }
    @Test void mapCreateIsPublicAndAppendsActualGeneratedId() throws Exception {
        mvc.perform(post("/api/admin/excel-maps").contentType(MediaType.APPLICATION_JSON).content("{\"excel_lbl_nm\":\"테스트표기\",\"trns_cd_grp_id\":\"JOB\",\"trns_cd\":\"BE\",\"map_role_cd\":\"MAIN\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        mvc.perform(get("/api/admin/excel-maps")).andExpect(status().isOk()).andExpect(jsonPath("$.data.data.items[1].map_id").value("2")).andExpect(jsonPath("$.data.data.items[1].map_role_cd").value("MAIN"));
        assertThat(audits.get(0).type()).isEqualTo("ADD"); assertThat(json.readTree(audits.get(0).after()).get("map_id").asText()).isEqualTo("2");
    }
    @ParameterizedTest @CsvSource({"excel_lbl_nm,엑셀 글자를 입력하세요.","trns_cd_grp_id,대응할 코드를 고르세요.","trns_cd,대응할 코드를 고르세요."})
    void mapCreateRequiredErrorsDoNotInsert(String field,String message) throws Exception {
        Map<String,String> row=new HashMap<>(Map.of("excel_lbl_nm","테스트표기","trns_cd_grp_id","JOB","trns_cd","BE","map_role_cd","MAIN")); row.put(field,"");
        mvc.perform(post("/api/admin/excel-maps").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(row)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field)).andExpect(jsonPath("$.message").value(message)); assertThat(maps).hasSize(1); assertThat(audits).isEmpty();
    }
    @Test void duplicateTargetTripleIs409() throws Exception {
        when(mapper.duplicateMap(any())).thenReturn(true);
        mvc.perform(post("/api/admin/excel-maps").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(new MapCreate("기존 표기","JOB","BE","MAIN",""))))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_CODE")).andExpect(jsonPath("$.message").value("이미 있는 대응입니다.")); assertThat(maps).hasSize(1); assertThat(audits).isEmpty();
    }
    @Test void sameLabelDifferentActiveTargetIsAllowed() throws Exception {
        mvc.perform(post("/api/admin/excel-maps").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(new MapCreate("기존 표기","JOB","OPS","MAIN","")))).andExpect(status().isOk()); assertThat(maps).hasSize(2);
    }
    @Test void inactiveMappingTarget404() throws Exception { when(mapper.activeTarget(any(),any())).thenReturn(false); mvc.perform(post("/api/admin/excel-maps").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(new MapCreate("테스트표기","JOB","OPS","MAIN","")))).andExpect(status().isNotFound()); assertThat(audits).isEmpty(); }
    @Test void mapPatchIsPublicPreservesIdentityAndAuditsEveryField() throws Exception {
        mvc.perform(patch("/api/admin/excel-maps/1").contentType(MediaType.APPLICATION_JSON).content("{\"map_role_cd\":\"SUB\",\"rmrk_desc\":\"수정 메모\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        mvc.perform(get("/api/admin/excel-maps")).andExpect(status().isOk()).andExpect(jsonPath("$.data.data.items[0].map_role_cd").value("SUB")).andExpect(jsonPath("$.data.data.items[0].rmrk_desc").value("수정 메모"))
            .andExpect(jsonPath("$.data.data.items[0].excel_lbl_nm").value("기존 표기")).andExpect(jsonPath("$.data.data.items[0].trns_cd").value("BE"));
        assertThat(audits).hasSize(2); assertThat(json.readTree(audits.get(1).before()).get("value").asText()).isEqualTo("이전 메모");
        assertThat(json.readTree(audits.get(1).after()).get("value").asText()).isEqualTo("수정 메모");
    }
    @Test void mapPatchMemo201RejectedBeforeMutation() throws Exception {
        mvc.perform(patch("/api/admin/excel-maps/1").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of("rmrk_desc","가".repeat(201)))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("rmrk_desc")); assertThat(maps.get(1L).memo()).isEqualTo("이전 메모"); assertThat(audits).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"excel_lbl_nm","trns_cd_grp_id","trns_cd"}) void mapIdentityPatchRejected(String field) throws Exception {
        mvc.perform(patch("/api/admin/excel-maps/1").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of(field,"다른 값","map_role_cd","SUB"))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field)); assertThat(maps.get(1L).role()).isEqualTo("MAIN"); assertThat(audits).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"{}","{\"rmrk_desc\":null}","{\"use_yn\":null}","{\"map_role_cd\":\"BAD\"}","{\"use_yn\":\"BAD\"}"}) void invalidPatchIs400(String body) throws Exception { mvc.perform(patch("/api/admin/excel-maps/1").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest()); assertThat(audits).isEmpty(); }
    @Test void emptyMemoClearsWhileAbsentMemoRetains() throws Exception {
        mvc.perform(patch("/api/admin/excel-maps/1").contentType(MediaType.APPLICATION_JSON).content("{\"use_yn\":\"N\"}")).andExpect(status().isOk()); assertThat(maps.get(1L).memo()).isEqualTo("이전 메모"); assertThat(maps).hasSize(1); assertThat(audits.get(0).type()).isEqualTo("UPDATE");
        mvc.perform(patch("/api/admin/excel-maps/1").contentType(MediaType.APPLICATION_JSON).content("{\"rmrk_desc\":\"\"}")).andExpect(status().isOk()); assertThat(maps.get(1L).memo()).isEmpty();
    }
    @Test void mapNoOpHasNoHistory() throws Exception { mvc.perform(patch("/api/admin/excel-maps/1").contentType(MediaType.APPLICATION_JSON).content("{\"map_role_cd\":\"MAIN\"}")).andExpect(status().isOk()); assertThat(audits).isEmpty(); }
    @Test void unknownMap404() throws Exception { mvc.perform(patch("/api/admin/excel-maps/999").contentType(MediaType.APPLICATION_JSON).content("{\"rmrk_desc\":\"수정\"}")).andExpect(status().isNotFound()); }
    @Test void paramSaveIsPublicPreservesPastAndSameMonthUpserts() throws Exception {
        saveParam("VAT_RATE","2026-11","{\"num_val\":11}",200); saveParam("VAT_RATE","2026-11","{\"num_val\":12}",200);
        assertThat(params).hasSize(1); assertThat(params.get("VAT_RATE:2026-11").number()).isEqualByComparingTo("12");
        mvc.perform(get("/api/admin/params/VAT_RATE/versions")).andExpect(status().isOk()).andExpect(jsonPath("$.data.data.items[0].aply_bgn_ym").value("2026-11"));
        assertThat(audits).allMatch(e -> e.groupId().equals("PARAM") && e.code().equals("VAT_RATE") && e.item().equals("적용 시작월"));
        assertThat(json.readTree(audits.get(0).before()).get("num_val").decimalValue()).isEqualByComparingTo("10");
        assertThat(json.readTree(audits.get(0).after()).get("aply_bgn_ym").asText()).isEqualTo("2026-11");
    }
    @ParameterizedTest @ValueSource(strings={"-1","101","0.00001","100000000000000","1.12345"}) void invalidNumericParamNeverWrites(String value) throws Exception {
        mvc.perform(put("/api/admin/params/TARGET_UTIL_RATE/versions/2026-11").contentType(MediaType.APPLICATION_JSON).content("{\"num_val\":"+value+"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("num_val")).andExpect(jsonPath("$.message").value("올바른 값을 입력하세요.")); assertThat(params).isEmpty(); assertThat(audits).isEmpty();
    }
    @ParameterizedTest @ValueSource(ints={0,100}) void percentBoundariesAccepted(int value) throws Exception { saveParam("TARGET_UTIL_RATE","2026-11","{\"num_val\":"+value+"}",200); assertThat(params.get("TARGET_UTIL_RATE:2026-11").number()).isEqualByComparingTo(Integer.toString(value)); }
    @ParameterizedTest @ValueSource(strings={"","가"}) void invalidTextParamNeverWrites(String text) throws Exception {
        String value=text.isEmpty()?text:text.repeat(201);
        mvc.perform(put("/api/admin/params/AR_AGING_DAYS/versions/2026-11").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of("txt_val",value))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("txt_val")).andExpect(jsonPath("$.message").value("올바른 값을 입력하세요.")); assertThat(audits).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"{}","{\"num_val\":1,\"txt_val\":\"문자\"}","{\"txt_val\":\"잘못된 유형\"}"}) void wrongParamKindIs400(String body) throws Exception { saveParam("VAT_RATE","2026-11",body,400); assertThat(params).isEmpty(); assertThat(audits).isEmpty(); }
    @Test void closedMonth409BeforeAnyWrite() throws Exception { mvc.perform(put("/api/admin/params/VAT_RATE/versions/2026-09").contentType(MediaType.APPLICATION_JSON).content("{\"num_val\":11}")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH")); assertThat(params).isEmpty(); assertThat(audits).isEmpty(); }
    @Test void noClosedRowsDoesNotInventBoundary() throws Exception { when(mapper.lastClosed()).thenReturn(null); saveParam("VAT_RATE","0001-01","{\"num_val\":0}",200); }
    @Test void unknownParam404() throws Exception { saveParam("UNKNOWN","2026-11","{\"num_val\":11}",404); mvc.perform(get("/api/admin/params/UNKNOWN/versions")).andExpect(status().isNotFound()); }
    @ParameterizedTest @ValueSource(strings={"0000-01","2026-13","2026-1"}) void invalidMonth400(String month) throws Exception { saveParam("VAT_RATE",month,"{\"num_val\":11}",400); assertThat(audits).isEmpty(); }
    @Test void databaseExceptionIsSanitizedAndRequestIdMatches() throws Exception {
        when(mapper.mapCount()).thenThrow(new IllegalStateException("SELECT credentials FROM internal"));
        mvc.perform(get("/api/admin/excel-maps").header("X-Request-Id","admin-error")).andExpect(status().isInternalServerError()).andExpect(header().string("X-Request-Id","admin-error"))
            .andExpect(jsonPath("$.requestId").value("admin-error")).andExpect(jsonPath("$.code").value("INTERNAL_ERROR")).andExpect(content().string(not(containsString("SELECT"))));
    }
}
