package com.sicost.admin;

import static com.sicost.admin.AdminModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.*;

@WebMvcTest(controllers=AdminController.class)
@Import({RequestIdFilter.class,ApiExceptionHandler.class,AdminService.class,CurrentUser.class,PermissionService.class,AdminApiTest.Time.class})
class AdminApiTest {
    @TestConfiguration static class Time { @Bean Clock clock(){return Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"),ZoneId.of("Asia/Seoul"));} }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping handlers;
    @MockBean AdminMapper mapper;
    CodeGroup biz=new CodeGroup("JOB","직무","BIZ","기본 단가(원)",null,null,1,"Y",2);
    CodeGroup system=new CodeGroup("CONTRACT_TYPE","계약 유형","SYSTEM",null,null,null,2,"Y",1);
    CodeRow be=new CodeRow("BE","백엔드",1,"Y","200000",null,null,null);
    CodeRow ops=new CodeRow("OPS","운영",2,"Y",null,null,null,null);
    ExcelMap mapping=new ExcelMap("1","기존 표기","JOB","BE","직무","백엔드","MAIN","원래 메모","Y");
    OperatingParam vat=new OperatingParam("VAT_RATE","2000-01","부가세",BigDecimal.TEN,null,"%","계산");
    @BeforeEach void setup() {
        when(mapper.group("JOB")).thenReturn(biz);when(mapper.group("CONTRACT_TYPE")).thenReturn(system);
        when(mapper.allCodes("JOB")).thenReturn(List.of(be,ops));
        when(mapper.allCodes("CONTRACT_TYPE")).thenReturn(List.of(new CodeRow("TK","턴키",1,"Y",null,null,null,null)));
        when(mapper.mapping("1")).thenReturn(mapping);
        when(mapper.source(eq("VAT_RATE"),anyString())).thenReturn(vat);
        when(mapper.lastClosed()).thenReturn("2026-09");
        when(mapper.activeTarget(any())).thenReturn(true);when(mapper.insertMapping(any())).thenReturn(7L);
    }
    ResultActions write(String method,String path,Object body)throws Exception {
        var request=switch(method){case "POST"->post(path);case "PATCH"->patch(path);default->put(path);};
        return mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }
    CodeRow change(CodeRow r,String field,String value) {
        return new CodeRow(r.dtl_cd(),field.equals("cd_nm")?value:r.cd_nm(),r.sort_seq(),field.equals("use_yn")?value:r.use_yn(),field.equals("attr1_val")?value:r.attr1_val(),r.attr2_val(),r.attr3_val(),r.aply_bgn_dt());
    }
    void untouched() {verify(mapper,never()).insertCode(any());verify(mapper,never()).updateCode(any());verify(mapper,never()).appendHistory(any());}
    @Test void publicGroupListIsFlatAndUsesDatabaseTotal()throws Exception {
        when(mapper.groups(any())).thenReturn(List.of(biz,system));when(mapper.groupCount()).thenReturn(36L);
        mvc.perform(get("/api/admin/code-groups")).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(36)).andExpect(jsonPath("$.data.page").value(1)).andExpect(jsonPath("$.data.size").value(50)).andExpect(jsonPath("$.data.items[0].attr1_nm").value("기본 단가(원)")).andExpect(jsonPath("$.data.items[1].cd_grp_se_cd").value("SYSTEM")).andExpect(jsonPath("$.data.data").doesNotExist());
    }
    @Test void managedQueryRetainsInactiveCodeAndName()throws Exception {
        when(mapper.codes(any())).thenReturn(List.of(change(ops,"use_yn","N")));when(mapper.codeCount("JOB")).thenReturn(12L);
        mvc.perform(get("/api/admin/code-groups/JOB/codes")).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].cd_nm").value("운영")).andExpect(jsonPath("$.data.items[0].use_yn").value("N")).andExpect(jsonPath("$.data.total").value(12));
    }
    @Test void mappingQueryKeepsJoinedNames()throws Exception {
        when(mapper.maps(any())).thenReturn(List.of(mapping));when(mapper.mapCount()).thenReturn(1L);
        mvc.perform(get("/api/admin/excel-maps")).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].map_id").value("1")).andExpect(jsonPath("$.data.items[0].cd_nm").value("백엔드"));
    }
    @Test void historyFilterAndCountReceiveIdenticalQuery()throws Exception {
        var event=new CodeChange(LocalDateTime.of(2026,10,22,9,0),"PARAM","기준값","VAT_RATE","UPDATE","적용 시작월","2000-01","2026-11",null);
        when(mapper.history(any())).thenReturn(List.of(event));when(mapper.historyCount(any())).thenReturn(81L);
        mvc.perform(get("/api/admin/code-history").param("group","PARAM").param("page","2")).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(81)).andExpect(jsonPath("$.data.items[0].chg_user_id").isEmpty());
        var q=org.mockito.ArgumentCaptor.forClass(AdminQuery.class);verify(mapper).history(q.capture());verify(mapper).historyCount(q.getValue());assertThat(q.getValue().group()).isEqualTo("PARAM");assertThat(q.getValue().offset()).isEqualTo(50);
    }
    @Test void currentParamsUseSeoulRuntimeMonth()throws Exception {
        when(mapper.params(any())).thenReturn(List.of(vat));when(mapper.paramCount(any())).thenReturn(1L);
        mvc.perform(get("/api/admin/params")).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].num_val").value(10));
        verify(mapper).params(argThat(q->q.month().equals("2026-10")));
    }
    @Test void versionsIncludeFutureWithoutMonthFilter()throws Exception {
        when(mapper.versionCount("VAT_RATE")).thenReturn(2L);when(mapper.versions(any())).thenReturn(List.of(new OperatingParam("VAT_RATE","2026-11","부가세",BigDecimal.valueOf(11),null,"%","계산"),vat));
        mvc.perform(get("/api/admin/params/VAT_RATE/versions")).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].aply_bgn_ym").value("2026-11"));
    }
    @ParameterizedTest @ValueSource(strings={"/api/admin/code-groups/MISSING/codes","/api/admin/params/MISSING/versions"})
    void unknownReadIs404(String path)throws Exception {mvc.perform(get(path)).andExpect(status().isNotFound());}
    @ParameterizedTest @ValueSource(strings={"page=0","size=30","sort=unknown,asc","sort=sort_seq;DROP TABLE,asc","sort=sort_seq,up"})
    void invalidPagingAndSortAre400(String query)throws Exception {mvc.perform(get("/api/admin/code-groups?"+query)).andExpect(status().isBadRequest());}
    @Test void anonymousCreatesBizGroupWithNullActorAndAddHistorySideEffect()throws Exception {
        mvc.perform(post("/api/admin/code-groups").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("cd_grp_id","NEW_GROUP","cd_grp_nm","새 그룹"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.meta").isMap()).andExpect(jsonPath("$.data").doesNotExist())
            .andExpect(header().exists("X-Request-Id"));
        verify(mapper).createGroup(argThat(w->w.input().cd_grp_id().equals("NEW_GROUP")&&w.actor()==null));verify(mapper).appendHistory(argThat(h->h.type().equals("ADD")&&h.code()==null&&h.actor()==null));
    }
    @ParameterizedTest @CsvSource({"cd_grp_id,A1","cd_grp_id,lower","cd_grp_id,''","cd_grp_nm,''"})
    void invalidGroupFieldsDoNotWrite(String field,String value)throws Exception {
        var body=new HashMap<>(Map.of("cd_grp_id","NEW","cd_grp_nm","새 그룹"));body.put(field,value);
        mvc.perform(post("/api/admin/code-groups").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.field").value(field)).andExpect(jsonPath("$.message").isNotEmpty());
        verify(mapper,never()).createGroup(any());verify(mapper,never()).appendHistory(any());
    }
    @ParameterizedTest @ValueSource(strings={"cd_grp_id","cd_grp_nm","attr1_nm","attr2_nm","attr3_nm"})
    void groupLengthsAreEnforced(String field)throws Exception {
        var body=new HashMap<>(Map.of("cd_grp_id","NEW","cd_grp_nm","새 그룹"));body.put(field,"A".repeat(field.equals("cd_grp_id")?31:field.equals("cd_grp_nm")?101:51));
        write("POST","/api/admin/code-groups",body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field));verify(mapper,never()).createGroup(any());
    }
    @Test void duplicateGroupIs409()throws Exception {
        mvc.perform(post("/api/admin/code-groups").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("cd_grp_id","JOB","cd_grp_nm","직무"))))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_CODE"))
            .andExpect(jsonPath("$.field").value("cd_grp_id")).andExpect(jsonPath("$.message").value("이미 있는 코드입니다."));
        verify(mapper,never()).createGroup(any());untouched();
    }
    @Test void anonymousFullSaveAddsCodeAndSingleAddHistory()throws Exception {
        mvc.perform(put("/api/admin/code-groups/JOB/codes").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(List.of(be,ops,new CodeRow("TEST1","테스트",3,"Y",null,null,null,null)))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.meta").isMap()).andExpect(jsonPath("$.data").doesNotExist());
        verify(mapper).insertCode(argThat(w->w.group().equals("JOB")&&w.row().dtl_cd().equals("TEST1")&&w.row().use_yn().equals("Y")&&w.actor()==null));
        verify(mapper).appendHistory(argThat(h->h.group().equals("JOB")&&h.type().equals("ADD")&&h.code().equals("TEST1")&&h.actor()==null));
    }
    @Test void missingExistingIdentityIs400BeforeAnyMutation()throws Exception {
        mvc.perform(put("/api/admin/code-groups/JOB/codes").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(List.of(ops))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.field").value("dtl_cd")).andExpect(jsonPath("$.message").isNotEmpty());
        untouched();
    }
    @Test void duplicateAndValidChangeRejectWholeRequest()throws Exception {write("PUT","/api/admin/code-groups/JOB/codes",List.of(change(be,"cd_nm","변경"),ops,be)).andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("이미 있는 코드입니다."));untouched();}
    @Test void systemNameOnlyIsAllowed()throws Exception {write("PUT","/api/admin/code-groups/CONTRACT_TYPE/codes",List.of(new CodeRow("TK","턴키(일괄)",1,"Y",null,null,null,null))).andExpect(status().isOk());verify(mapper).updateCode(argThat(w->w.changed().equals(Set.of("cd_nm"))));}
    @Test void systemStatusPriorityRejectsNameToo()throws Exception {
        mvc.perform(put("/api/admin/code-groups/CONTRACT_TYPE/codes").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(List.of(new CodeRow("TK","변경",1,"N",null,null,null,null)))))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SYSTEM_CODE_LOCKED"))
            .andExpect(jsonPath("$.message").value("시스템 코드는 사용 중지할 수 없습니다."));
        untouched();
    }
    @Test void systemAttributesRejectWholeRequest()throws Exception {write("PUT","/api/admin/code-groups/CONTRACT_TYPE/codes",List.of(new CodeRow("TK","변경",1,"Y","값",null,null,null))).andExpect(status().isConflict());untouched();}
    @Test void systemNewCodeIsRejected()throws Exception {write("PUT","/api/admin/code-groups/CONTRACT_TYPE/codes",List.of(new CodeRow("TK","턴키",1,"Y",null,null,null,null),new CodeRow("NEW","신규",2,"Y",null,null,null,null))).andExpect(status().isConflict());untouched();}
    @ParameterizedTest @CsvSource({"dtl_cd,코드를 입력하세요.","cd_nm,이름을 입력하세요."})
    void blankRowFieldPreservesAll(String field,String message)throws Exception {
        var body=json.convertValue(be,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});body.put(field,"");
        write("PUT","/api/admin/code-groups/JOB/codes",List.of(body,ops)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("[0]."+field)).andExpect(jsonPath("$.message").value(message));untouched();
    }
    @Test void changedAttributeAndStatusHaveIndependentHistory()throws Exception {
        mvc.perform(put("/api/admin/code-groups/JOB/codes").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(List.of(change(change(be,"attr1_val","250000"),"use_yn","N"),ops))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        verify(mapper).updateCode(argThat(w->w.row().use_yn().equals("N")&&w.changed().equals(Set.of("attr1_val","use_yn"))&&w.actor()==null));
        verify(mapper).appendHistory(argThat(h->h.type().equals("UPDATE")&&h.item().equals("attr1_val")&&h.before().equals("200000")&&h.after().equals("250000")&&h.actor()==null));
        verify(mapper).appendHistory(argThat(h->h.type().equals("DISABLE")&&h.item().equals("use_yn")));
    }
    @Test void reenableRecordsEnable()throws Exception {
        when(mapper.allCodes("JOB")).thenReturn(List.of(change(be,"use_yn","N"),ops));
        mvc.perform(put("/api/admin/code-groups/JOB/codes").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(List.of(be,ops))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        verify(mapper).updateCode(argThat(w->w.row().use_yn().equals("Y")&&w.changed().equals(Set.of("use_yn"))));
        verify(mapper).appendHistory(argThat(h->h.type().equals("ENABLE")&&h.item().equals("use_yn")&&h.before().equals("N")&&h.after().equals("Y")&&h.actor()==null));
    }
    @Test void anonymousMappingCreateAndAuditUsesReturnedId()throws Exception {
        mvc.perform(post("/api/admin/excel-maps").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("excel_lbl_nm","테스트표기","trns_cd_grp_id","JOB","trns_cd","BE","map_role_cd","MAIN"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.meta").isMap()).andExpect(jsonPath("$.data").doesNotExist());
        verify(mapper).insertMapping(argThat(w->w.input().excel_lbl_nm().equals("테스트표기")&&w.input().trns_cd_grp_id().equals("JOB")&&w.input().trns_cd().equals("BE")&&w.input().map_role_cd().equals("MAIN")&&w.actor()==null));
        verify(mapper).appendHistory(argThat(h->h.item().equals("map_id=7:ADD")&&h.type().equals("ADD")&&h.actor()==null));
    }
    @ParameterizedTest @CsvSource({"excel_lbl_nm,엑셀 글자를 입력하세요.","trns_cd_grp_id,대응할 코드를 고르세요.","trns_cd,대응할 코드를 고르세요."})
    void mappingRequiredFields(String field,String message)throws Exception {
        var body=new HashMap<>(Map.of("excel_lbl_nm","테스트","trns_cd_grp_id","JOB","trns_cd","BE","map_role_cd","MAIN"));body.put(field,"");
        mvc.perform(post("/api/admin/excel-maps").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.field").value(field)).andExpect(jsonPath("$.message").value(message));
        verify(mapper,never()).insertMapping(any());verify(mapper,never()).appendHistory(any());
    }
    @Test void mappingInactiveTargetIs400()throws Exception {when(mapper.activeTarget(any())).thenReturn(false);write("POST","/api/admin/excel-maps",Map.of("excel_lbl_nm","표기","trns_cd_grp_id","JOB","trns_cd","BE","map_role_cd","MAIN")).andExpect(status().isBadRequest());verify(mapper,never()).insertMapping(any());}
    @Test void duplicateMappingIs409()throws Exception {
        when(mapper.mappingExists(any())).thenReturn(true);
        mvc.perform(post("/api/admin/excel-maps").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("excel_lbl_nm","표기","trns_cd_grp_id","JOB","trns_cd","BE","map_role_cd","MAIN"))))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_CODE"))
            .andExpect(jsonPath("$.message").value("이미 있는 대응입니다."));
        verify(mapper,never()).insertMapping(any());verify(mapper,never()).appendHistory(any());
    }
    @Test void anonymousMappingPatchPreservesIdentityAndRecordsAllChanges()throws Exception {
        mvc.perform(patch("/api/admin/excel-maps/1").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("map_role_cd","SUB","rmrk_desc","수정 메모"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.meta").isMap()).andExpect(jsonPath("$.data").doesNotExist());
        verify(mapper).updateMapping(argThat(w->w.original().equals(mapping)&&w.patch().supplied().equals(Set.of("map_role_cd","rmrk_desc"))));
        verify(mapper).appendHistory(argThat(h->h.item().equals("map_id=1:rmrk_desc")&&h.before().equals("원래 메모")&&h.after().equals("수정 메모")));
        verify(mapper).appendHistory(argThat(h->h.item().equals("map_id=1:map_role_cd")&&h.before().equals("MAIN")&&h.after().equals("SUB")));
        verify(mapper, times(2)).appendHistory(argThat(h -> h.actor() == null));
        verify(mapper, never()).insertMapping(any());
    }
    @Test void mappingSoftDisableNeverDeletes()throws Exception {
        mvc.perform(patch("/api/admin/excel-maps/1").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("use_yn","N"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        verify(mapper).updateMapping(argThat(w->w.original().equals(mapping)&&w.patch().use_yn().equals("N")&&w.patch().supplied().equals(Set.of("use_yn"))));
        verify(mapper).appendHistory(argThat(h->h.type().equals("DISABLE")&&h.item().equals("map_id=1:use_yn")&&h.before().equals("Y")&&h.after().equals("N")&&h.actor()==null));
    }
    @Test void mappingMemo201RejectedWithoutHistory()throws Exception {
        mvc.perform(patch("/api/admin/excel-maps/1").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("rmrk_desc","X".repeat(201)))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.field").value("rmrk_desc")).andExpect(jsonPath("$.message").isNotEmpty());
        verify(mapper,never()).updateMapping(any());verify(mapper,never()).appendHistory(any());
    }
    @ParameterizedTest @ValueSource(strings={"excel_lbl_nm","trns_cd_grp_id","trns_cd","map_id"})
    void mappingIdentityMutationIs400(String field)throws Exception {
        mvc.perform(patch("/api/admin/excel-maps/1").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(field,"replace","map_role_cd","SUB"))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value(field));
        verify(mapper,never()).updateMapping(any());verify(mapper,never()).appendHistory(any());
    }
    @Test void emptyPatchIs400()throws Exception {write("PATCH","/api/admin/excel-maps/1",Map.of()).andExpect(status().isBadRequest());}
    @Test void nullMemoIs400()throws Exception {var body=new HashMap<String,Object>();body.put("rmrk_desc",null);write("PATCH","/api/admin/excel-maps/1",body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("rmrk_desc"));}
    @Test void emptyMemoClearsOnlyMemo()throws Exception {write("PATCH","/api/admin/excel-maps/1",Map.of("rmrk_desc","")).andExpect(status().isOk());verify(mapper).updateMapping(argThat(w->w.patch().rmrk_desc().equals("")&&w.patch().supplied().equals(Set.of("rmrk_desc"))));}
    @Test void unknownMapIs404()throws Exception {
        mvc.perform(patch("/api/admin/excel-maps/2").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("rmrk_desc","memo"))))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        verify(mapper,never()).updateMapping(any());verify(mapper,never()).appendHistory(any());
    }
    @Test void anonymousFutureParamWriteCopiesSourceAndAuditsMonthAndValue()throws Exception {
        mvc.perform(put("/api/admin/params/VAT_RATE/versions/2026-11").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("num_val",11))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.meta").isMap()).andExpect(jsonPath("$.data").doesNotExist());
        verify(mapper).saveParam(argThat(w->w.source().equals(vat)&&w.month().equals("2026-11")&&w.actor()==null));
        verify(mapper).appendHistory(argThat(h->h.group().equals("PARAM")&&h.code().equals("VAT_RATE")&&h.item().equals("적용 시작월")&&h.after().equals("2026-11")));
        verify(mapper).appendHistory(argThat(h->h.item().equals("2026-11:num_val")&&h.after().equals("11")));
        verify(mapper, times(2)).appendHistory(argThat(h -> h.group().equals("PARAM")
            && h.code().equals("VAT_RATE") && h.type().equals("UPDATE") && h.actor() == null));
    }
    @ParameterizedTest @ValueSource(strings={"-1","101","0.00001","100000000000000"})
    void invalidNumericIs400WithNoMutation(String value)throws Exception {
        mvc.perform(put("/api/admin/params/VAT_RATE/versions/2026-11").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("num_val",new BigDecimal(value)))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.field").value("num_val")).andExpect(jsonPath("$.message").value("올바른 값을 입력하세요."));
        verify(mapper,never()).saveParam(any());verify(mapper,never()).appendHistory(any());
    }
    @ParameterizedTest @ValueSource(ints={0,100})
    void numericPercentBoundsAreAccepted(int value)throws Exception {write("PUT","/api/admin/params/VAT_RATE/versions/2026-11",Map.of("num_val",value)).andExpect(status().isOk());}
    @Test void closedMonthIs409AndNoMutation()throws Exception {
        mvc.perform(put("/api/admin/params/VAT_RATE/versions/2026-09").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("num_val",11))))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH"))
            .andExpect(jsonPath("$.field").value("month")).andExpect(jsonPath("$.message").isNotEmpty());
        verify(mapper,never()).saveParam(any());verify(mapper,never()).appendHistory(any());
    }
    @ParameterizedTest @ValueSource(strings={"2026-13","2026-1","0000-01"})
    void invalidMonthIs400(String month)throws Exception {write("PUT","/api/admin/params/VAT_RATE/versions/"+month,Map.of("num_val",11)).andExpect(status().isBadRequest());}
    @Test void unknownParameterIs404()throws Exception {write("PUT","/api/admin/params/UNKNOWN/versions/2026-11",Map.of("num_val",11)).andExpect(status().isNotFound());}
    @ParameterizedTest @ValueSource(strings={"","X"})
    void textLengthValidation(String value)throws Exception {
        when(mapper.source(eq("TEXT"),anyString())).thenReturn(new OperatingParam("TEXT","2000-01","문자",null,"기존",null,null));
        write("PUT","/api/admin/params/TEXT/versions/2026-11",Map.of("txt_val",value.isEmpty()?value:value.repeat(201))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("txt_val"));verify(mapper,never()).saveParam(any());
    }
    @Test void paramRejectsBothValues()throws Exception {write("PUT","/api/admin/params/VAT_RATE/versions/2026-11",Map.of("num_val",11,"txt_val","문자")).andExpect(status().isBadRequest());}
    @Test void paramRejectsWrongType()throws Exception {write("PUT","/api/admin/params/VAT_RATE/versions/2026-11",Map.of("txt_val","문자")).andExpect(status().isBadRequest());}
    @Test void approvedFixtureMatchesEveryAdminHandlerMethodAndLiteralPath()throws Exception {
        Map<String,Object> contract;
        try(var in=new ClassPathResource("contracts/openapi.yaml").getInputStream()) {
            contract=new org.yaml.snakeyaml.Yaml().load(in);
        }
        Map<?,?> paths=(Map<?,?>)contract.get("paths");
        Set<String> expected=new TreeSet<>();
        paths.forEach((path,item)->{
            if(path.toString().startsWith("/api/admin/")) {
                ((Map<?,?>)item).forEach((method,definition)->{
                    if(Set.of("get","post","put","patch","delete").contains(method.toString())) {
                        Map<?,?> operation=(Map<?,?>)definition;
                        assertThat(operation.get("operationId")).isInstanceOf(String.class);
                        Map<?,?> responses=(Map<?,?>)operation.get("responses");
                        // OpenAPI permits a response range for successful writes.
                        assertThat(responses.containsKey("200") || responses.containsKey("2XX"))
                            .as("successful response for %s %s",method,path).isTrue();
                        expected.add(method.toString().toUpperCase(Locale.ROOT)+" "+path);
                    }
                });
            }
        });
        Set<String> actual=new TreeSet<>();
        handlers.getHandlerMethods().forEach((mapping,handler)->{
            if(handler.getBeanType().equals(AdminController.class)) {
                assertThat(mapping.getPatternValues()).hasSize(1);
                assertThat(mapping.getMethodsCondition().getMethods()).hasSize(1);
                mapping.getMethodsCondition().getMethods().forEach(method->
                    mapping.getPatternValues().forEach(path->actual.add(method.name()+" "+path)));
            }
        });
        assertThat(expected).isNotEmpty();
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
    }
}
