package com.sicost;

import com.sicost.persistence.CostMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.Yaml;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApiContractTest {
    CostMapper db;
    MockMvc http;
    ObjectMapper json = new ObjectMapper();
    Map<String,Object> biz, system, code, param;
    @BeforeEach void setup() {
        db=mock(CostMapper.class);
        CostService service=new CostService(db,new Access.PermissionService());
        http=MockMvcBuilders.standaloneSetup(new CostController(service)).setControllerAdvice(new ApiErrors()).addFilters(new RequestTrace()).build();
        biz=row("cd_grp_id","JOB","cd_grp_nm","직무","cd_grp_se_cd","BIZ","codeCount",1,"sort_seq",1,"use_yn","Y");
        system=row("cd_grp_id","CONTRACT_TYPE","cd_grp_nm","계약유형","cd_grp_se_cd","SYSTEM","codeCount",1,"sort_seq",1,"use_yn","Y");
        code=row("dtl_cd","BE","cd_nm","백엔드","sort_seq",1,"use_yn","Y","attr1_val",null,"attr2_val",null,"attr3_val",null,"aply_bgn_dt",null);
        param=row("oprt_std_id","VAT_RATE","oprt_std_nm","부가세율","num_val",10,"txt_val",null,"unit_nm","%","aply_bgn_ym","2000-01","use_plc_desc","청구");
        when(db.group("JOB")).thenReturn(biz);when(db.group("CONTRACT_TYPE")).thenReturn(system);
        when(db.groups()).thenReturn(List.of(biz,system));when(db.codes("JOB")).thenReturn(List.of(code));
        when(db.options("JOB")).thenReturn(List.of(row("code","BE","name","백엔드","attr1",null,"attr2",null,"attr3",null)));
        when(db.maps()).thenReturn(List.of(mapping()));when(db.excelMap("1")).thenReturn(mapping());
        when(db.versions("VAT_RATE")).thenReturn(List.of(param));when(db.currentParams(anyString())).thenReturn(List.of(param));
        when(db.effective(eq("VAT_RATE"),anyString())).thenReturn(param);when(db.lastClosedMonth()).thenReturn("2026-09");
        when(db.history(any())).thenReturn(List.of(row("cd_grp_id","JOB","cd_grp_nm","직무","dtl_cd","BE","cd_chg_ty_cd","UPDATE","item_nm","cd_nm","bfr_val","이전","aft_val","백엔드","chg_at","2026-10-01 00:00:00","chg_user_id",null)));
        when(db.menus()).thenReturn(List.of(row("menuId",1L,"parentId",null,"name","묶음","screenId",null,"icon","folder","mobile",true),row("menuId",2L,"parentId",1L,"name","프로젝트","screenId","UI-01","icon",null,"mobile",true)));
        when(db.months("2026-09","2026-10")).thenReturn(List.of(row("month","2026-09","status","CLOSED"),row("month","2026-10","status","OPEN")));
    }
    static Map<String,Object> row(Object... pairs) {Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return result;}
    Map<String,Object> mapping(){return row("map_id","1","excel_lbl_nm","테스트표기","trns_cd_grp_id","JOB","trns_cd","BE","cd_grp_nm","직무","cd_nm","백엔드","map_role_cd","MAIN","rmrk_desc",null,"use_yn","Y");}
    String body(Object value) throws Exception {return json.writeValueAsString(value);}
    @Test void durableOpenApiFixtureIsLoadedFromClasspath() throws Exception {
        try(var stream=new ClassPathResource("contracts/openapi.yaml").getInputStream()) {
            Map<String,Object> contract=new Yaml().load(stream);
            assertEquals("3.0.3",contract.get("openapi"));
            var paths=(Map<?,?>)contract.get("paths");
            assertTrue(paths.containsKey("/api/admin/params/{key}/versions/{month}"));
            assertEquals(List.of(),contract.get("security"));
            assertFalse(((Map<?,?>)contract.get("components")).containsKey("securitySchemes"));
            var context=Objects.requireNonNull(http.getDispatcherServlet().getWebApplicationContext());
            var handlers=context.getBean(RequestMappingHandlerMapping.class).getHandlerMethods();
            Set<String> actual=new HashSet<>();
            handlers.forEach((mapping,handler)->mapping.getPatternValues().forEach(path->mapping.getMethodsCondition().getMethods().forEach(method->actual.add(method.name()+" "+path))));
            Set<String> expected=new HashSet<>();
            paths.forEach((path,item)->((Map<?,?>)item).forEach((method,operation)->{
                if(Set.of("get","post","put","patch","delete","head","options").contains(method.toString())) {
                    expected.add(method.toString().toUpperCase(Locale.ROOT)+" "+path);
                    assertEquals(List.of(),((Map<?,?>)operation).get("security"));
                    assertFalse(((Map<?,?>)operation).containsKey("x-roles"));
                }
            }));
            assertEquals(expected,actual,"Every durable OpenAPI operation must have its own controller handler");
        }
    }
    @Test void healthMenusMonthsAndActiveOptionsAreUnauthenticated() throws Exception {
        http.perform(get("/api/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP")).andExpect(header().exists("X-Request-Id"));
        http.perform(get("/api/menus")).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].children[0].screenId").value("UI-01")).andExpect(jsonPath("$.data[0].parentId").doesNotExist());
        http.perform(get("/api/month-close").param("from","2026-09").param("to","2026-10")).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].status").value("CLOSED")).andExpect(jsonPath("$.data[1].status").value("OPEN"));
        http.perform(get("/api/codes/JOB")).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].code").value("BE"));verify(db).options("JOB");
    }
    @Test void requestTraceIsServerGeneratedAndClearedAfterFailure() throws Exception {
        when(db.groups()).thenThrow(new IllegalStateException("internal"));
        var result=http.perform(get("/api/admin/code-groups").header("X-Request-Id","untrusted"))
            .andExpect(status().isInternalServerError()).andReturn();
        String id=result.getResponse().getHeader("X-Request-Id");
        assertNotEquals("untrusted",id);
        assertDoesNotThrow(()->UUID.fromString(id));
        assertEquals(id,json.readTree(result.getResponse().getContentAsString()).get("requestId").asText());
        assertNull(RequestTrace.currentId());
    }
    @Test void absentRecordsRejectNullAndEmptyRepositoryResults() throws Exception {
        for(boolean empty:List.of(false,true)) {
            Map<String,Object> missing=empty?Map.of():null;
            when(db.group("MISSING")).thenReturn(missing);
            when(db.effective("MISSING","2026-10")).thenReturn(missing);
            when(db.excelMap("99")).thenReturn(missing);
            http.perform(get("/api/codes/MISSING")).andExpect(status().isNotFound());
            http.perform(get("/api/params/MISSING").param("month","2026-10")).andExpect(status().isNotFound());
            http.perform(patch("/api/admin/excel-maps/99").contentType(MediaType.APPLICATION_JSON).content("{\"use_yn\":\"N\"}"))
                .andExpect(status().isNotFound());
        }
        verify(db,never()).updateMap(any());
    }
    @Test void listCodeGroupsReturnsTypedPage() throws Exception {
        http.perform(get("/api/admin/code-groups")).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.items[0].cd_grp_id").value("JOB")).andExpect(jsonPath("$.data.total").value(2)).andExpect(jsonPath("$.data.size").value(50));
        verify(db).groups();
    }
    @Test void listManagedCodesReturnsTypedPageAndRejectsUnknownGroup() throws Exception {
        http.perform(get("/api/admin/code-groups/JOB/codes")).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].dtl_cd").value("BE")).andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.page").value(1));
        http.perform(get("/api/admin/code-groups/MISSING/codes")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        verify(db).codes("JOB");verify(db,never()).codes("MISSING");
    }
    @Test void listExcelMapsReturnsTypedPageAndRejectsInvalidSort() throws Exception {
        http.perform(get("/api/admin/excel-maps")).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.items[0].map_id").value("1")).andExpect(jsonPath("$.data.items[0].map_role_cd").value("MAIN")).andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.size").value(50));
        http.perform(get("/api/admin/excel-maps").param("sort","invalid")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("sort")).andExpect(jsonPath("$.message").isString());
        verify(db,times(2)).maps();
    }
    @Test void listCodeHistoryReturnsNullableActorAndAuditValues() throws Exception {
        http.perform(get("/api/admin/code-history")).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].cd_chg_ty_cd").value("UPDATE")).andExpect(jsonPath("$.data.items[0].chg_user_id").value(org.hamcrest.Matchers.nullValue())).andExpect(jsonPath("$.data.total").value(1));
        verify(db).history(null);
    }
    @Test void listCurrentParamsReturnsTypedPageAndRejectsInvalidPage() throws Exception {
        http.perform(get("/api/admin/params")).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.items[0].oprt_std_id").value("VAT_RATE")).andExpect(jsonPath("$.data.items[0].num_val").value(10)).andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.size").value(50));
        http.perform(get("/api/admin/params").param("page","0")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("page")).andExpect(jsonPath("$.message").isString());
        verify(db,times(2)).currentParams(anyString());
    }
    @Test void listParamVersionsReturnsTypedPageAndRejectsUnknownKey() throws Exception {
        http.perform(get("/api/admin/params/VAT_RATE/versions")).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.items[0].aply_bgn_ym").value("2000-01")).andExpect(jsonPath("$.data.items[0].num_val").value(10)).andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.size").value(50));
        http.perform(get("/api/admin/params/MISSING/versions")).andExpect(status().isNotFound()).andExpect(jsonPath("$.success").value(false)).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        verify(db).versions("VAT_RATE");
    }
    @Test void historyFiltersAndWholeDatasetTotalsArePreserved() throws Exception {
        http.perform(get("/api/admin/code-history").param("group","PARAM").param("size","20")).andExpect(status().isOk()).andExpect(jsonPath("$.data.size").value(20));verify(db).history("PARAM");
        when(db.groups()).thenReturn(Collections.nCopies(55,biz));
        http.perform(get("/api/admin/code-groups").param("page","2").param("size","50")).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(55)).andExpect(jsonPath("$.data.items.length()").value(5));
    }
    @Test void groupCreationAuthAllowsAnonymousAndRecordsSideEffects() throws Exception {
        http.perform(post("/api/admin/code-groups").contentType(MediaType.APPLICATION_JSON).content("{\"cd_grp_id\":\"TEST_GROUP\",\"cd_grp_nm\":\"테스트\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        verify(db).insertGroup(argThat(r->"TEST_GROUP".equals(r.get("cd_grp_id"))&&r.containsKey("actor")&&r.get("actor")==null));
        verify(db).appendHistory(argThat(r->r.get("dtl_cd")==null&&"ADD".equals(r.get("cd_chg_ty_cd"))&&r.containsKey("actor")&&r.get("actor")==null));
    }
    @Test void groupValidationAndBusinessNumericIdentifierNeverPersist() throws Exception {
        http.perform(post("/api/admin/code-groups").contentType(MediaType.APPLICATION_JSON).content("{\"cd_grp_id\":\"TEST\",\"cd_grp_nm\":\"\"}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("cd_grp_nm"));
        http.perform(post("/api/admin/code-groups").contentType(MediaType.APPLICATION_JSON).content("{\"cd_grp_id\":\"TEST1\",\"cd_grp_nm\":\"테스트\"}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("cd_grp_id"));
        verify(db,never()).insertGroup(any());verify(db,never()).appendHistory(any());
    }
    @Test void codeSaveAuthAllowsAnonymousAndRecordsInsertAndUpdateSideEffects() throws Exception {
        var modified=new LinkedHashMap<>(code);modified.put("cd_nm","새 이름");var added=new LinkedHashMap<>(code);added.put("dtl_cd","TEST1");added.put("sort_seq",2);
        http.perform(put("/api/admin/code-groups/JOB/codes").contentType(MediaType.APPLICATION_JSON).content(body(List.of(modified,added)))).andExpect(status().isOk()).andExpect(jsonPath("$.data.saved").value(true));
        verify(db).updateCode(argThat(r->((Map<?,?>)r.get("changes")).size()==1&&r.containsKey("actor")&&r.get("actor")==null));verify(db).insertCode(argThat(r->"TEST1".equals(r.get("dtl_cd"))&&r.containsKey("actor")&&r.get("actor")==null));
        verify(db).appendHistory(argThat(r->"UPDATE".equals(r.get("cd_chg_ty_cd"))&&"cd_nm".equals(r.get("item_nm"))&&"백엔드".equals(r.get("bfr_val"))));verify(db).appendHistory(argThat(r->"ADD".equals(r.get("cd_chg_ty_cd"))));
    }
    @Test void duplicateAndMissingExistingRowsRejectBeforeAnyWrite() throws Exception {
        http.perform(put("/api/admin/code-groups/JOB/codes").contentType(MediaType.APPLICATION_JSON).content(body(List.of(code,code)))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_CODE"));
        http.perform(put("/api/admin/code-groups/JOB/codes").contentType(MediaType.APPLICATION_JSON).content("[]")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("dtl_cd"));
        verify(db,never()).updateCode(any());verify(db,never()).insertCode(any());verify(db,never()).appendHistory(any());
    }
    @Test void systemMixedDisableRejectsNameUpdateAndNewCodes() throws Exception {
        var tk=new LinkedHashMap<>(code);tk.put("dtl_cd","TK");when(db.codes("CONTRACT_TYPE")).thenReturn(List.of(tk));
        var changed=new LinkedHashMap<>(tk);changed.put("cd_nm","턴키(일괄)");changed.put("use_yn","N");
        http.perform(put("/api/admin/code-groups/CONTRACT_TYPE/codes").contentType(MediaType.APPLICATION_JSON).content(body(List.of(changed)))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SYSTEM_CODE_LOCKED")).andExpect(jsonPath("$.message").value("시스템 코드는 사용 중지할 수 없습니다."));
        verify(db,never()).updateCode(any());verify(db,never()).appendHistory(any());
    }
    @Test void bizDisableEnableAreSoftAndAudited() throws Exception {
        var disabled=new LinkedHashMap<>(code);disabled.put("use_yn","N");
        http.perform(put("/api/admin/code-groups/JOB/codes").contentType(MediaType.APPLICATION_JSON).content(body(List.of(disabled)))).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        verify(db).appendHistory(argThat(r->"DISABLE".equals(r.get("cd_chg_ty_cd"))));
        when(db.codes("JOB")).thenReturn(List.of(disabled));
        http.perform(put("/api/admin/code-groups/JOB/codes").contentType(MediaType.APPLICATION_JSON).content(body(List.of(code)))).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        verify(db).appendHistory(argThat(r->"ENABLE".equals(r.get("cd_chg_ty_cd"))));
    }
    @Test void mappingCreateAndPatchAuthAllowAnonymousWithBusinessRestrictionsAndSideEffects() throws Exception {
        String create="{\"excel_lbl_nm\":\"테스트표기\",\"trns_cd_grp_id\":\"JOB\",\"trns_cd\":\"BE\",\"map_role_cd\":\"MAIN\"}";
        http.perform(post("/api/admin/excel-maps").contentType(MediaType.APPLICATION_JSON).content(create)).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        verify(db).insertMap(argThat(r->"테스트표기".equals(r.get("excel_lbl_nm"))&&r.containsKey("actor")&&r.get("actor")==null));verify(db).appendHistory(argThat(r->"ADD".equals(r.get("cd_chg_ty_cd"))&&r.containsKey("actor")&&r.get("actor")==null));
        when(db.insertMap(any())).thenThrow(new org.springframework.dao.DuplicateKeyException("SQL secret"));
        http.perform(post("/api/admin/excel-maps").contentType(MediaType.APPLICATION_JSON).content(create)).andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("이미 있는 대응입니다."));
        http.perform(patch("/api/admin/excel-maps/1").contentType(MediaType.APPLICATION_JSON).content("{\"map_role_cd\":\"SUB\",\"rmrk_desc\":\"수정\",\"use_yn\":\"N\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        verify(db).updateMap(argThat(r->((Map<?,?>)r.get("changes")).keySet().equals(Set.of("map_role_cd","rmrk_desc","use_yn"))));
        http.perform(patch("/api/admin/excel-maps/1").contentType(MediaType.APPLICATION_JSON).content("{\"excel_lbl_nm\":\"변경\"}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("excel_lbl_nm"));
    }
    @Test void mappingMissingInputInactiveTargetAndLongMemoReject() throws Exception {
        http.perform(post("/api/admin/excel-maps").contentType(MediaType.APPLICATION_JSON).content("{\"excel_lbl_nm\":\"\"}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("엑셀 글자를 입력하세요."));
        http.perform(post("/api/admin/excel-maps").contentType(MediaType.APPLICATION_JSON).content("{\"excel_lbl_nm\":\"표기\",\"trns_cd_grp_id\":\"JOB\",\"trns_cd\":\"INACTIVE\",\"map_role_cd\":\"MAIN\"}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("trns_cd"));
        http.perform(patch("/api/admin/excel-maps/1").contentType(MediaType.APPLICATION_JSON).content(body(Map.of("rmrk_desc","가".repeat(201))))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("rmrk_desc"));verify(db,never()).insertMap(any());verify(db,never()).updateMap(any());
    }
    @Test void paramVersionAuthAllowsAnonymousAndRecordsSideEffects() throws Exception {
        http.perform(get("/api/params/VAT_RATE").param("month","2026-10")).andExpect(status().isOk()).andExpect(jsonPath("$.data.num_val").value(10));
        http.perform(put("/api/admin/params/VAT_RATE/versions/2026-11").contentType(MediaType.APPLICATION_JSON).content("{\"num_val\":11}")).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        verify(db).saveParam(argThat(r->"2026-11".equals(r.get("aply_bgn_ym"))&&"부가세율".equals(r.get("oprt_std_nm"))));
        verify(db).appendHistory(argThat(r->"PARAM".equals(r.get("cd_grp_id"))&&"VAT_RATE".equals(r.get("dtl_cd"))&&"UPDATE".equals(r.get("cd_chg_ty_cd"))&&"적용 시작월".equals(r.get("item_nm"))&&r.containsKey("actor")&&r.get("actor")==null));
    }
    @Test void closedMonthAndInvalidNumbersNeverWriteValuesOrHistory() throws Exception {
        http.perform(put("/api/admin/params/VAT_RATE/versions/2026-09").contentType(MediaType.APPLICATION_JSON).content("{\"num_val\":11}")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH"));
        for(int value:List.of(-1,101))http.perform(put("/api/admin/params/VAT_RATE/versions/2026-11").contentType(MediaType.APPLICATION_JSON).content(body(Map.of("num_val",value)))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("num_val"));
        verify(db,never()).saveParam(any());verify(db,never()).appendHistory(any());
    }
    @Test void percentBoundariesAndTextValidation() throws Exception {
        for(int value:List.of(0,100))http.perform(put("/api/admin/params/VAT_RATE/versions/2026-11").contentType(MediaType.APPLICATION_JSON).content(body(Map.of("num_val",value)))).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        var text=new LinkedHashMap<>(param);text.put("num_val",null);text.put("txt_val","문자");when(db.versions("TEXT_PARAM")).thenReturn(List.of(text));
        for(String value:List.of("","가".repeat(201)))http.perform(put("/api/admin/params/TEXT_PARAM/versions/2026-11").contentType(MediaType.APPLICATION_JSON).content(body(Map.of("txt_val",value)))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("txt_val"));
    }
    @Test void notFoundInvalidPageMalformedInputAndErrorLeakage() throws Exception {
        http.perform(get("/api/codes/MISSING")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        http.perform(get("/api/params/MISSING").param("month","2026-10")).andExpect(status().isNotFound()).andExpect(jsonPath("$.success").value(false));
        http.perform(get("/api/admin/code-groups").param("size","17")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("page"));
        http.perform(get("/api/month-close").param("from","bad").param("to","2026-10")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("from"));
        http.perform(post("/api/admin/code-groups").contentType(MediaType.APPLICATION_JSON).content("{")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
        when(db.groups()).thenThrow(new IllegalStateException("SQL password internal stack"));
        var result=http.perform(get("/api/admin/code-groups")).andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("SERVER_ERROR")).andExpect(jsonPath("$.meta").isMap()).andReturn();
        assertFalse(result.getResponse().getContentAsString().contains("password"));assertEquals(result.getResponse().getHeader("X-Request-Id"),json.readTree(result.getResponse().getContentAsString()).get("requestId").asText());
    }
}
