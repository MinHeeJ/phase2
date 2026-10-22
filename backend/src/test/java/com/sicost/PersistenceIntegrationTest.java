package com.sicost;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
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
// A build/test image may receive only the URL; require explicit test DB credentials.
@EnabledIfEnvironmentVariable(named="DATABASE_URL",matches=".*\\S.*")
@EnabledIfEnvironmentVariable(named="DATABASE_USER",matches=".*\\S.*")
@EnabledIfEnvironmentVariable(named="DATABASE_PASSWORD",matches=".*\\S.*")
class PersistenceIntegrationTest {
    @Autowired MockMvc http;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Test void completeMigrationInventoryAndSeeds() throws Exception {
        assertEquals(80,jdbc.queryForObject("select count(*) from information_schema.tables where table_schema in ('cmn','sys','mst','bsn','prj','asgn','hr','cst','bill','fin','ai') and table_type='BASE TABLE'",Integer.class));
        assertEquals(36,jdbc.queryForObject("select count(*) from cmn.cd_grp",Integer.class));
        assertEquals(189,jdbc.queryForObject("select count(*) from cmn.cd_dtl",Integer.class));
        assertEquals(41,jdbc.queryForObject("select count(*) from sys.menu",Integer.class));
        assertEquals(5,jdbc.queryForObject("select count(*) from sys.role",Integer.class));
        assertEquals(155,jdbc.queryForObject("select count(*) from sys.role_menu_auth",Integer.class));
        assertEquals(10,jdbc.queryForObject("select count(*) from fin.mth_cls",Integer.class));
        http.perform(get("/api/codes/JOB")).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(12));
        assertEquals("200000",jdbc.queryForObject("select attr1_val from cmn.cd_dtl where cd_grp_id='POSITION' and dtl_cd='TL'",String.class));
        assertEquals("2026-10-05 00:00:00",jdbc.queryForObject("select to_char(cls_at,'YYYY-MM-DD HH24:MI:SS') from fin.mth_cls where cls_ym='2026-09'",String.class));
    }
    @Test @Transactional void realSoftDisableFilteringAndAudit() throws Exception {
        var rows=json.readTree(http.perform(get("/api/admin/code-groups/JOB/codes").param("size","100")).andReturn().getResponse().getContentAsString()).get("data").get("items");
        for(var row:rows)if(row.get("dtl_cd").asText().equals("OPS"))((com.fasterxml.jackson.databind.node.ObjectNode)row).put("use_yn","N");
        http.perform(put("/api/admin/code-groups/JOB/codes").contentType("application/json").content(rows.toString())).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        assertEquals("N",jdbc.queryForObject("select use_yn from cmn.cd_dtl where cd_grp_id='JOB' and dtl_cd='OPS'",String.class));
        var options=http.perform(get("/api/codes/JOB")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(json.readTree(options).get("data").toString().contains("\"OPS\""));
        assertEquals(1,jdbc.queryForObject("select count(*) from cmn.cd_hist where cd_grp_id='JOB' and dtl_cd='OPS' and cd_chg_ty_cd='DISABLE' and chg_user_id is null",Integer.class));
    }
    @Test @Transactional void futureParamUpsertAndClosedMonthRollback() throws Exception {
        http.perform(put("/api/admin/params/VAT_RATE/versions/2026-11").contentType("application/json").content("{\"num_val\":11}")).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        http.perform(get("/api/params/VAT_RATE").param("month","2026-10")).andExpect(status().isOk()).andExpect(jsonPath("$.data.num_val").value(10));
        http.perform(get("/api/params/VAT_RATE").param("month","2026-11")).andExpect(status().isOk()).andExpect(jsonPath("$.data.num_val").value(11));
        http.perform(put("/api/admin/params/VAT_RATE/versions/2026-11").contentType("application/json").content("{\"num_val\":12}")).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        assertEquals(1,jdbc.queryForObject("select count(*) from cmn.oprt_std where oprt_std_id='VAT_RATE' and aply_bgn_ym='2026-11'",Integer.class));
        int before=jdbc.queryForObject("select count(*) from cmn.cd_hist",Integer.class);
        http.perform(put("/api/admin/params/VAT_RATE/versions/2026-09").contentType("application/json").content("{\"num_val\":13}")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSED_MONTH"));
        assertEquals(before,jdbc.queryForObject("select count(*) from cmn.cd_hist",Integer.class));
    }
    @Test @Transactional void invalidBatchDoesNotChangeAnyRowOrAudit() throws Exception {
        var rows=json.readTree(http.perform(get("/api/admin/code-groups/JOB/codes").param("size","100")).andReturn().getResponse().getContentAsString()).get("data").get("items");
        ((com.fasterxml.jackson.databind.node.ObjectNode)rows.get(0)).put("cd_nm","반영되면 안 됨");
        ((com.fasterxml.jackson.databind.node.ArrayNode)rows).add(rows.get(0).deepCopy());
        int history=jdbc.queryForObject("select count(*) from cmn.cd_hist",Integer.class);
        http.perform(put("/api/admin/code-groups/JOB/codes").contentType("application/json").content(rows.toString())).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_CODE"));
        assertEquals(0,jdbc.queryForObject("select count(*) from cmn.cd_dtl where cd_nm='반영되면 안 됨'",Integer.class));
        assertEquals(history,jdbc.queryForObject("select count(*) from cmn.cd_hist",Integer.class));
    }
}
