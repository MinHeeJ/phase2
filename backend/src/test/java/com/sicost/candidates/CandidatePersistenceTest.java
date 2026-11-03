package com.sicost.candidates;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.*;
import com.sicost.workforce.WorkforceTestSupport;
import java.util.*;
import org.junit.jupiter.api.*;

class CandidatePersistenceTest {
    WorkforceTestSupport support;
    @BeforeEach void isolatedDatabase()throws Exception {support=new WorkforceTestSupport();}
    @AfterEach void cleanup()throws Exception {if(support!=null)support.close();}
    JsonNode body(){return support.json.valueToTree(Map.of("cand_nm","후보-"+UUID.randomUUID(),"cand_se_cd","FL","job_cd","BE","hope_untprc_amt",5000000,"avail_dt","2026-11-01","skill",List.of("JAVA")));}
    long create()throws Exception {
        var result=support.mvc.perform(post("/api/candidates").contentType("application/json").content(support.json.writeValueAsBytes(body()))).andExpect(status().isOk()).andReturn();
        return support.json.readTree(result.getResponse().getContentAsByteArray()).path("data").path("cand_id").longValue();
    }
    void contact(long id,String date,String status,String memo)throws Exception {
        support.mvc.perform(post("/api/candidates/"+id+"/contacts").contentType("application/json").content(support.json.writeValueAsBytes(Map.of("cntct_dt",date,"cntct_stts_cd",status,"memo",memo))))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andExpect(jsonPath("$.data.cand_id").value(id));
    }
    @Test void generatedIdentityChildrenAndAuditReadback()throws Exception {
        long id=create();assertThat(support.candidates.get(id).candidate().cand_no()).isEqualTo(String.format(Locale.ROOT,"CAND-%04d",id));
        assertThat(support.candidates.get(id).skill()).containsExactly("JAVA");
        assertThat(support.scalar("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='hr.cand' AND obj_rec_id='"+id+"' AND chg_user_id IS NULL")).isEqualTo(1);
    }
    @Test void historicalAppendAndEqualTimestampTiePreserveEarlierRows()throws Exception {
        long id=create();contact(id,"2026-10-20","INTV","새 연락");
        String before=support.value("SELECT row_to_json(h)::text FROM hr.cand_cntct_hist h WHERE cand_id="+id+" ORDER BY cntct_hist_id LIMIT 1");
        contact(id,"2026-10-10","HOLD","과거 연락");
        assertThat(support.candidates.get(id).candidate().cntct_stts_cd()).isEqualTo("INTV");
        assertThat(support.value("SELECT row_to_json(h)::text FROM hr.cand_cntct_hist h WHERE cand_id="+id+" ORDER BY cntct_hist_id LIMIT 1")).isEqualTo(before);
        contact(id,"2026-10-20","DONE","동일일 나중 기록");
        assertThat(support.candidates.get(id).candidate().cntct_stts_cd()).isEqualTo("DONE");
        assertThat(support.scalar("SELECT count(*) FROM hr.cand_cntct_hist WHERE cand_id="+id)).isEqualTo(3);
    }
    @Test void conversionCreatesOneDeveloperNoPayrollOrAssignmentAndKeepsSignedOnLaterContact()throws Exception {
        long id=create();long salaries=support.scalar("SELECT count(*) FROM cst.mth_cst"),assignments=support.scalar("SELECT count(*) FROM asgn.asgn");
        var result=support.mvc.perform(post("/api/candidates/"+id+"/convert").contentType("application/json").content("{\"developer\":{}}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.candidate.cntct_stts_cd").value("SIGNED")).andReturn();
        long dev=support.json.readTree(result.getResponse().getContentAsByteArray()).path("data").path("developer").path("dev_id").longValue();
        assertThat(support.scalar("SELECT src_cand_id FROM mst.dev WHERE dev_id="+dev)).isEqualTo(id);
        assertThat(support.scalar("SELECT dev_id FROM hr.cand WHERE cand_id="+id)).isEqualTo(dev);
        assertThat(support.scalar("SELECT count(*) FROM cst.mth_cst")).isEqualTo(salaries);assertThat(support.scalar("SELECT count(*) FROM asgn.asgn")).isEqualTo(assignments);
        contact(id,"2026-10-20","HOLD","전환 뒤 기록");assertThat(support.candidates.get(id).candidate().cntct_stts_cd()).isEqualTo("SIGNED");
        support.mvc.perform(post("/api/candidates/"+id+"/convert").contentType("application/json").content("{\"developer\":{}}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_CONVERTED"));
        assertThat(support.scalar("SELECT count(*) FROM mst.dev WHERE src_cand_id="+id)).isEqualTo(1);
    }
    @Test void auditFailureRollsBackCandidateAndSkillsTogether()throws Exception {
        support.sql("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT candidate_test_reject CHECK(tbl_nm!='hr.cand') NOT VALID");
        assertThatThrownBy(()->support.candidates.create(body())).isInstanceOf(RuntimeException.class);
        assertThat(support.scalar("SELECT count(*) FROM hr.cand")).isZero();assertThat(support.scalar("SELECT count(*) FROM hr.cand_skl")).isZero();
    }
    @Test void inactiveCandidateIsExcludedAndThirtyDayBoundaryIsStrict()throws Exception {
        long first=create(),second=create(),inactive=create();
        support.sql("UPDATE hr.cand SET last_cntct_dt='2026-09-23' WHERE cand_id="+first+"; UPDATE hr.cand SET last_cntct_dt='2026-09-22' WHERE cand_id="+second+"; UPDATE hr.cand SET use_yn='N' WHERE cand_id="+inactive);
        support.mvc.perform(get("/api/candidates")).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2))
            .andExpect(jsonPath("$.data.kpi.uncontacted_over_30_days_count").value(1));
        support.mvc.perform(get("/api/candidates").param("uncontacted_only","true")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.items[0].cand_id").value(second));
    }
}
