package com.sicost.workforce;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.sicost.developers.*;
import java.util.*;
import org.junit.jupiter.api.*;

class WorkforceForecastPersistenceTest {
    WorkforceTestSupport support;
    @BeforeEach void database()throws Exception {support=new WorkforceTestSupport();}
    @AfterEach void cleanup()throws Exception {if(support!=null)support.close();}
    long developer() {
        var input=DeveloperInput.parse(support.json.valueToTree(Map.of("dev_nm","예측-"+UUID.randomUUID(),"emp_se_cd","FT","main_job_cd","BE","sub_job",List.of(),"skill",List.of())),false);
        return support.context.getBean(DeveloperService.class).create(input).item().dev_id();
    }
    long project()throws Exception {return support.scalar("INSERT INTO prj.prj(prj_no,prj_nm,cntrct_ty_cd,prj_stts_cd) VALUES('T-"+UUID.randomUUID().toString().substring(0,15)+"','예측 시험','IN','RUN') RETURNING prj_id");}
    @Test void workingDayUnionConfirmedMmAndCostsAreReadOnly()throws Exception {
        long dev=developer();
        support.sql("INSERT INTO cst.mth_cst(dev_id,cst_ym,emp_se_cd,slry_amt) VALUES("+dev+",'2026-10','FT',3000000)");
        support.sql("INSERT INTO mst.dev_work_stts_hist(dev_id,work_stts_cd,bgn_dt,end_dt) VALUES("+dev+",'LEAVE','2026-11-01','2026-11-15'),("+dev+",'VACATION','2026-11-05','2026-11-10')");
        String before=support.value("SELECT row_to_json(c)::text FROM cst.mth_cst c WHERE dev_id="+dev);
        support.mvc.perform(get("/api/workforce/forecast").param("months","1")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].available_mm").value(0.50))
            .andExpect(jsonPath("$.data.items[0].supply_mm").value(0.50))
            .andExpect(jsonPath("$.data.items[0].idle_cost").value(1500000));
        assertThat(support.value("SELECT row_to_json(c)::text FROM cst.mth_cst c WHERE dev_id="+dev)).isEqualTo(before);
    }
    @Test void planSubtractsConfirmedOnceAndReturnsUnfilledDemand()throws Exception {
        long dev=developer(),project=project();
        long assignment=support.scalar("INSERT INTO asgn.asgn(asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt) VALUES('T-"+UUID.randomUUID().toString().substring(0,15)+"',"+project+","+dev+",'BE','2026-11-01','2026-11-30') RETURNING asgn_id");
        support.sql("INSERT INTO asgn.asgn_mth(asgn_id,work_ym,mm_qty) VALUES("+assignment+",'2026-11',0.30); INSERT INTO prj.psn_plan(prj_id,plan_ym,job_cd,grd_cd,need_mm_qty) VALUES("+project+",'2026-11','BE','',0.80)");
        var result=support.workforce.forecast(Map.of("months","1"));
        assertThat(result.items()).hasSize(1);assertThat(result.items().get(0).supply_mm()).isEqualByComparingTo("0.70");
        assertThat(result.items().get(0).demand_mm()).isEqualByComparingTo("0.50");
        assertThat(result.items().get(0).remaining_mm()).isEqualByComparingTo("0.20");
        assertThat(support.scalar("SELECT count(*) FROM asgn.asgn_mth WHERE asgn_id="+assignment)).isEqualTo(1);
    }
    @Test void excessConfirmedMmClampsSupplyAndDoesNotWriteFinancialSources()throws Exception {
        long dev=developer(),project=project();
        long assignment=support.scalar("INSERT INTO asgn.asgn(asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt) VALUES('T-"+UUID.randomUUID().toString().substring(0,15)+"',"+project+","+dev+",'BE','2026-11-01','2026-11-30') RETURNING asgn_id");
        support.sql("INSERT INTO asgn.asgn_mth(asgn_id,work_ym,mm_qty) VALUES("+assignment+",'2026-11',1.20)");
        long audits=support.scalar("SELECT count(*) FROM sys.data_chg_hist");
        var result=support.workforce.forecast(Map.of("months","1"));
        assertThat(result.items().get(0).supply_mm()).isZero();assertThat(result.items().get(0).warnings()).contains("확정 MM 1.0 초과");
        assertThat(support.scalar("SELECT count(*) FROM sys.data_chg_hist")).isEqualTo(audits);
        assertThat(support.value("SELECT mm_qty::text FROM asgn.asgn_mth WHERE asgn_id="+assignment)).isEqualTo("1.20");
    }
}
