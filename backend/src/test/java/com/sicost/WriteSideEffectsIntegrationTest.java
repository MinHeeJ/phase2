package com.sicost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
// Evaluate before Spring starts so incomplete test DB settings never open a connection.
@EnabledIfEnvironmentVariable(named="DATABASE_URL", matches=".*\\S.*")
@EnabledIfEnvironmentVariable(named="DATABASE_USER", matches=".*\\S.*")
@EnabledIfEnvironmentVariable(named="DATABASE_PASSWORD", matches=".*\\S.*")
@Transactional
class WriteSideEffectsIntegrationTest {
    @Autowired MockMvc http;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @Test void createCodeGroupAuthBusinessAndSideEffects() throws Exception {
        int nextSort=jdbc.queryForObject("select coalesce(max(sort_seq),0)+1 from cmn.cd_grp",Integer.class);
        http.perform(post("/api/admin/code-groups").contentType("application/json")
                .content("{\"cd_grp_id\":\"CONTRACT_TEST_GROUP\",\"cd_grp_nm\":\"계약 검증 그룹\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.saved").value(true));
        var group=jdbc.queryForMap("select cd_grp_se_cd,sort_seq,created_by from cmn.cd_grp where cd_grp_id='CONTRACT_TEST_GROUP'");
        assertEquals("BIZ",group.get("cd_grp_se_cd"));assertEquals(nextSort,group.get("sort_seq"));assertNull(group.get("created_by"));
        assertEquals(1,jdbc.queryForObject("select count(*) from cmn.cd_hist where cd_grp_id='CONTRACT_TEST_GROUP' and cd_chg_ty_cd='ADD' and dtl_cd is null and chg_user_id is null",Integer.class));
        http.perform(get("/api/admin/code-groups").param("size","100"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[?(@.cd_grp_id == 'CONTRACT_TEST_GROUP')].cd_grp_nm").value(org.hamcrest.Matchers.contains("계약 검증 그룹")));
        http.perform(get("/api/admin/code-history").param("group","CONTRACT_TEST_GROUP"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].cd_chg_ty_cd").value("ADD"))
                .andExpect(jsonPath("$.data.items[0].dtl_cd").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.items[0].chg_user_id").value(org.hamcrest.Matchers.nullValue()));
        int groups=jdbc.queryForObject("select count(*) from cmn.cd_grp",Integer.class);
        int history=historyCount();
        http.perform(post("/api/admin/code-groups").contentType("application/json")
                .content("{\"cd_grp_id\":\"INVALID1\",\"cd_grp_nm\":\"숫자 금지\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("cd_grp_id")).andExpect(jsonPath("$.message").isString());
        assertEquals(groups,jdbc.queryForObject("select count(*) from cmn.cd_grp",Integer.class));assertEquals(history,historyCount());
    }

    @Test void saveCodesAuthCreatedByUpdatedAtUpdatedByAndSideEffects() throws Exception {
        var response=http.perform(get("/api/admin/code-groups/POSITION/codes").param("size","100"))
                .andExpect(status().isOk()).andReturn().getResponse();
        ArrayNode rows=(ArrayNode)json.readTree(response.getContentAsString()).path("data").path("items");
        ObjectNode tl=null;
        for(JsonNode row:rows)if("TL".equals(row.path("dtl_cd").asText()))tl=(ObjectNode)row;
        assertNotNull(tl);assertEquals("200000",tl.path("attr1_val").asText());
        tl.put("attr1_val","250000");tl.put("cd_nm","계약 검증 TL");
        int nextSort=jdbc.queryForObject("select coalesce(max(sort_seq),0)+1 from cmn.cd_dtl where cd_grp_id='POSITION'",Integer.class);
        rows.add(json.createObjectNode().put("dtl_cd","CONTRACT_TEST").put("cd_nm","계약 검증 신규").put("sort_seq",nextSort).put("use_yn","Y"));
        http.perform(put("/api/admin/code-groups/POSITION/codes").contentType("application/json").content(rows.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.saved").value(true));
        var inserted=jdbc.queryForMap("select use_yn,created_by from cmn.cd_dtl where cd_grp_id='POSITION' and dtl_cd='CONTRACT_TEST'");
        assertEquals("Y",inserted.get("use_yn"));assertNull(inserted.get("created_by"));
        var updated=jdbc.queryForMap("select attr1_val,updated_at,updated_by from cmn.cd_dtl where cd_grp_id='POSITION' and dtl_cd='TL'");
        assertEquals("250000",updated.get("attr1_val"));assertNotNull(updated.get("updated_at"));assertNull(updated.get("updated_by"));
        assertEquals(1,jdbc.queryForObject("select count(*) from cmn.cd_hist where cd_grp_id='POSITION' and dtl_cd='TL' and cd_chg_ty_cd='UPDATE' and item_nm='attr1_val' and bfr_val='200000' and aft_val='250000' and chg_user_id is null",Integer.class));
        assertEquals(1,jdbc.queryForObject("select count(*) from cmn.cd_hist where cd_grp_id='POSITION' and dtl_cd='CONTRACT_TEST' and cd_chg_ty_cd='ADD' and chg_user_id is null",Integer.class));
        http.perform(get("/api/admin/code-groups/POSITION/codes").param("size","100"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[?(@.dtl_cd == 'TL')].cd_nm").value(org.hamcrest.Matchers.contains("계약 검증 TL")));
        http.perform(get("/api/admin/code-history").param("group","POSITION").param("size","100"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[?(@.item_nm == 'attr1_val')].aft_val").value(org.hamcrest.Matchers.hasItem("250000")));
    }

    @Test void createExcelMapAuthAndSideEffectsIncludeNullActor() throws Exception {
        http.perform(post("/api/admin/excel-maps").contentType("application/json")
                .content("{\"excel_lbl_nm\":\"계약 검증 표기\",\"trns_cd_grp_id\":\"JOB\",\"trns_cd\":\"BE\",\"map_role_cd\":\"MAIN\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.saved").value(true));
        var map=jdbc.queryForMap("select map_role_cd,use_yn,created_by from cmn.excel_map where excel_lbl_nm='계약 검증 표기' and trns_cd_grp_id='JOB' and trns_cd='BE'");
        assertEquals("MAIN",map.get("map_role_cd"));assertEquals("Y",map.get("use_yn"));assertNull(map.get("created_by"));
        assertEquals(1,jdbc.queryForObject("select count(*) from cmn.cd_hist where cd_grp_id='JOB' and dtl_cd='BE' and cd_chg_ty_cd='ADD' and item_nm='excel_lbl_nm' and aft_val='계약 검증 표기' and chg_user_id is null",Integer.class));
        http.perform(get("/api/admin/excel-maps").param("size","100"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[?(@.excel_lbl_nm == '계약 검증 표기')].trns_cd").value(org.hamcrest.Matchers.contains("BE")));
        http.perform(get("/api/admin/code-history").param("group","JOB").param("size","100"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[?(@.item_nm == 'excel_lbl_nm')].aft_val").value(org.hamcrest.Matchers.hasItem("계약 검증 표기")));
    }

    @Test void patchExcelMapAuthSideEffectsAndSourceBackedDisableState() throws Exception {
        http.perform(post("/api/admin/excel-maps").contentType("application/json")
                .content("{\"excel_lbl_nm\":\"계약 검증 상태\",\"trns_cd_grp_id\":\"JOB\",\"trns_cd\":\"BE\",\"map_role_cd\":\"MAIN\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.saved").value(true));
        Long id=jdbc.queryForObject("select map_id from cmn.excel_map where excel_lbl_nm='계약 검증 상태' and trns_cd_grp_id='JOB' and trns_cd='BE'",Long.class);
        var source=jdbc.queryForMap("select * from cmn.cd_dtl where cd_grp_id='JOB' and dtl_cd='BE'");
        var oldHistory=jdbc.queryForList("select * from cmn.cd_hist order by cd_hist_id");
        int history=historyCount();
        http.perform(patch("/api/admin/excel-maps/{mapId}",id).contentType("application/json")
                .content("{\"map_role_cd\":\"SUB\",\"rmrk_desc\":\"수정 메모\",\"use_yn\":\"N\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.saved").value(true));
        var saved=jdbc.queryForMap("select * from cmn.excel_map where map_id=?",id);
        assertEquals("N",saved.get("use_yn"));assertEquals("SUB",saved.get("map_role_cd"));assertEquals("수정 메모",saved.get("rmrk_desc"));
        assertEquals("계약 검증 상태",saved.get("excel_lbl_nm"));assertEquals("JOB",saved.get("trns_cd_grp_id"));assertEquals("BE",saved.get("trns_cd"));
        assertEquals(source,jdbc.queryForMap("select * from cmn.cd_dtl where cd_grp_id='JOB' and dtl_cd='BE'"));
        assertEquals(history+3,historyCount());
        assertEquals(oldHistory,jdbc.queryForList("select * from cmn.cd_hist order by cd_hist_id limit ?",history));
        assertEquals(1,jdbc.queryForObject("select count(*) from cmn.cd_hist where cd_grp_id='JOB' and dtl_cd='BE' and cd_chg_ty_cd='DISABLE' and item_nm='use_yn' and bfr_val='Y' and aft_val='N' and chg_user_id is null",Integer.class));
        assertEquals(1,jdbc.queryForObject("select count(*) from cmn.cd_hist where cd_grp_id='JOB' and dtl_cd='BE' and cd_chg_ty_cd='UPDATE' and item_nm='rmrk_desc' and bfr_val is null and aft_val='수정 메모' and chg_user_id is null",Integer.class));
        http.perform(get("/api/admin/excel-maps").param("size","100"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[?(@.excel_lbl_nm == '계약 검증 상태')].use_yn").value(org.hamcrest.Matchers.contains("N")));
        history=historyCount();
        http.perform(patch("/api/admin/excel-maps/{mapId}",id).contentType("application/json").content(json.writeValueAsString(java.util.Map.of("rmrk_desc","가".repeat(201)))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("rmrk_desc"));
        http.perform(patch("/api/admin/excel-maps/{mapId}",id).contentType("application/json").content("{\"excel_lbl_nm\":\"교체 금지\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("excel_lbl_nm"));
        assertEquals(saved,jdbc.queryForMap("select * from cmn.excel_map where map_id=?",id));assertEquals(history,historyCount());
    }

    @Test void saveParamAuthSideEffectsInsertUpsertAndPastValuePreservation() throws Exception {
        http.perform(put("/api/admin/params/VAT_RATE/versions/2026-11").contentType("application/json").content("{\"num_val\":11}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.saved").value(true));
        assertEquals(1,jdbc.queryForObject("select count(*) from cmn.oprt_std where oprt_std_id='VAT_RATE' and aply_bgn_ym='2026-11' and num_val=11 and created_by is null",Integer.class));
        assertEquals(1,jdbc.queryForObject("select count(*) from cmn.cd_hist where cd_grp_id='PARAM' and dtl_cd='VAT_RATE' and cd_chg_ty_cd='UPDATE' and item_nm='적용 시작월' and aft_val='2026-11' and chg_user_id is null",Integer.class));
        http.perform(get("/api/admin/params/VAT_RATE/versions"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].aply_bgn_ym").value("2026-11")).andExpect(jsonPath("$.data.items[0].num_val").value(11));
        http.perform(get("/api/admin/code-history").param("group","PARAM"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[?(@.item_nm == '적용 시작월')].dtl_cd").value(org.hamcrest.Matchers.hasItem("VAT_RATE")));
        http.perform(put("/api/admin/params/VAT_RATE/versions/2026-11").contentType("application/json").content("{\"num_val\":12}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.saved").value(true));
        assertEquals(1,jdbc.queryForObject("select count(*) from cmn.oprt_std where oprt_std_id='VAT_RATE' and aply_bgn_ym='2026-11'",Integer.class));
        http.perform(get("/api/params/VAT_RATE").param("month","2026-11"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.num_val").value(12));
        http.perform(get("/api/params/VAT_RATE").param("month","2026-10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.num_val").value(10));
    }

    private int historyCount() {
        return jdbc.queryForObject("select count(*) from cmn.cd_hist",Integer.class);
    }
}
