package com.sicost.staffplans;

import static com.sicost.staffplans.ProjectStaffPlanModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.sql.*;
import java.math.BigDecimal;
import java.util.*;
import com.sicost.common.api.ApiException;
import com.sicost.common.security.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.mybatis.spring.*;

class ProjectStaffPlanPersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl, user, password, database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    ProjectStaffPlanService service;
    ProjectStaffPlanMapper mapper;
    @BeforeEach void restoreIsolatedDatabase() throws Exception {
        adminUrl = System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl != null && !adminUrl.isBlank(), "Real PostgreSQL integration requires TEST_DATABASE_URL");
        user = System.getenv("TEST_DATABASE_USER"); password = System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user != null && password != null, "Test database connection settings are absent");
        database = "sicost_test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = DriverManager.getConnection(adminUrl, user, password); var statement = admin.createStatement()) {
            com.sicost.support.TestDatabaseDdl.create(statement, database);
        }
        source = new DriverManagerDataSource(adminUrl.substring(0, adminUrl.lastIndexOf('/') + 1) + database, user, password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory = new SqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/staffplans/ProjectStaffPlanMapper.xml")); factory.afterPropertiesSet();
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(ProjectStaffPlanMapper.class);
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class, ProjectStaffPlanService.class, CurrentUser.class, PermissionService.class);
        context.registerBean(ProjectStaffPlanMapper.class, () -> mapper);
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class, () -> new DataSourceTransactionManager(source));
        context.refresh(); service = context.getBean(ProjectStaffPlanService.class);
        sql("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cntrct_ty_cd,cntrct_bgn_dt,cntrct_end_dt,trgt_cst_amt,trgt_mrgn_rt) VALUES(1,'P-2601','프로젝트','TK','2026-10-01','2026-11-30',8000000,20),(2,'P-2602','다른 프로젝트','IN','2026-10-01','2026-11-30',NULL,NULL)");
        sql("INSERT INTO mst.dev(dev_id,mng_no,dev_nm,emp_se_cd) VALUES(1,'K-0001','개발자','FT')");
        sql("INSERT INTO asgn.asgn(asgn_id,asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt) VALUES(1,'A1',1,1,'BE','2026-10-01','2026-11-30'),(2,'A2',2,1,'BE','2026-10-01','2026-11-30')");
        sql("INSERT INTO asgn.asgn_mth(asgn_id,work_ym,mm_qty) VALUES(1,'2026-10',1.50),(1,'2026-11',2.00),(2,'2026-10',9.00)");
    }
    @AfterEach void removeIsolatedDatabase() throws Exception {
        if (context != null) context.close(); if (source == null) return;
        try (Connection admin = DriverManager.getConnection(adminUrl, user, password); var statement = admin.createStatement()) {
            com.sicost.support.TestDatabaseDdl.drop(statement, database);
        }
    }
    void sql(String sql) throws Exception { try (var c = source.getConnection(); var s = c.createStatement()) { s.execute(sql); } }
    String value(String sql) throws Exception { try (var c = source.getConnection(); var s = c.createStatement(); var rows = s.executeQuery(sql)) { assertThat(rows.next()).isTrue(); return rows.getString(1); } }
    StaffPlanInput cell(String month, String job, String grade, String mm) { return new StaffPlanInput(month, job, grade, new BigDecimal(mm), null); }
    String snapshot() throws Exception { return value("SELECT json_build_object('plan',(SELECT json_agg(p ORDER BY psn_plan_id) FROM prj.psn_plan p),'history',(SELECT json_agg(h ORDER BY data_chg_hist_id) FROM sys.data_chg_hist h))::text"); }
    String originals() throws Exception { return value("SELECT json_build_object('project',(SELECT row_to_json(p) FROM prj.prj p WHERE p.prj_id=1),'actual',(SELECT json_agg(m ORDER BY asgn_id,work_ym) FROM asgn.asgn_mth m))::text"); }
    @Test void saveReadbackMaterializationActualScopeAndSingleSummary() throws Exception {
        String before = originals();
        service.saveProjectStaffPlans(1, List.of(cell("2026-10", "BE", "", "2"), cell("2026-11", "BE", "", "2")));
        var result = service.getProjectStaffPlans(1);
        assertThat(result.items()).hasSize(2);
        assertThat(result.items().get(0).need_psn_cnt()).isEqualByComparingTo("2");
        assertThat(result.items().get(0).src_need_id()).isNull();
        assertThat(result.actual()).containsExactly(new ActualMM("BE", new BigDecimal("3.50")));
        assertThat(result.items().stream().map(StaffPlan::need_mm_qty).reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("4");
        assertThat(value("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='prj.psn_plan' AND obj_rec_id='1' AND chg_user_id IS NULL AND bfr_val IS NOT NULL AND aft_val LIKE '%4.00 MM' ")).isEqualTo("1");
        assertThat(originals()).isEqualTo(before);
        assertThat(service.getProjectStaffPlans(2).items()).isEmpty();
    }
    @Test void sourceRowsSurviveReplacementCeilingZeroAndEmpty() throws Exception {
        sql("INSERT INTO mst.ptnr(ptnr_id,ptnr_no,ptnr_nm,ptnr_se_cd,biz_reg_no) VALUES(1,'C-TEST','고객','CUST','123-45-67890')");
        sql("INSERT INTO bsn.oppt(oppt_id,oppt_no,oppt_nm,cust_ptnr_id) VALUES(1,'O1','기회',1)");
        sql("INSERT INTO bsn.oppt_need(need_id,oppt_id,job_cd,bgn_ym) VALUES(1,1,'FE','2026-10')");
        sql("INSERT INTO prj.psn_plan(prj_id,plan_ym,job_cd,grd_cd,need_mm_qty,need_psn_cnt,src_need_id) VALUES(1,'2026-10','FE','',1,1,1),(2,'2026-10','BE','',5,5,NULL)");
        String sourceRow = value("SELECT row_to_json(p)::text FROM prj.psn_plan p WHERE src_need_id=1");
        service.saveProjectStaffPlans(1, List.of(cell("2026-10", "BE", "", "2.01"), cell("2026-11", "BE", "", "0")));
        assertThat(value("SELECT need_psn_cnt FROM prj.psn_plan WHERE prj_id=1 AND job_cd='BE'")).isEqualTo("3.00");
        assertThat(value("SELECT count(*) FROM prj.psn_plan WHERE prj_id=1 AND need_mm_qty=0")).isEqualTo("0");
        service.saveProjectStaffPlans(1, List.of());
        assertThat(value("SELECT row_to_json(p)::text FROM prj.psn_plan p WHERE src_need_id=1")).isEqualTo(sourceRow);
        assertThat(value("SELECT count(*) FROM prj.psn_plan WHERE prj_id=1 AND src_need_id IS NULL")).isEqualTo("0");
        assertThat(value("SELECT need_mm_qty FROM prj.psn_plan WHERE prj_id=2")).isEqualTo("5.00");
    }
    @Test void validationAndCollisionLeaveDatabaseAndHistoryIdentical() throws Exception {
        service.saveProjectStaffPlans(1, List.of(cell("2026-10", "BE", "", "2"))); String before = snapshot();
        assertThatThrownBy(() -> service.saveProjectStaffPlans(1, List.of(cell("2026-10", "BE", "", "2"), cell("2026-10", "BE", "", "3")))).isInstanceOf(ApiException.class);
        assertThat(snapshot()).isEqualTo(before);
        sql("UPDATE cmn.cd_dtl SET use_yn='N' WHERE cd_grp_id='JOB' AND dtl_cd='FE'");
        assertThatThrownBy(() -> service.saveProjectStaffPlans(1, List.of(cell("2026-10", "FE", "", "2")))).isInstanceOf(ApiException.class);
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void shrinkRetainsOutsidePeriodAndExplicitRemovalWorks() throws Exception {
        service.saveProjectStaffPlans(1, List.of(cell("2026-11", "BE", "", "2")));
        sql("UPDATE prj.prj SET cur_end_dt='2026-10-31' WHERE prj_id=1");
        service.saveProjectStaffPlans(1, List.of(cell("2026-11", "BE", "", "2")));
        String before = snapshot();
        assertThatThrownBy(() -> service.saveProjectStaffPlans(1, List.of(cell("2026-11", "BE", "", "3")))).isInstanceOf(ApiException.class);
        assertThat(snapshot()).isEqualTo(before);
        service.saveProjectStaffPlans(1, List.of()); assertThat(service.getProjectStaffPlans(1).items()).isEmpty();
    }
    @Test void auditFailureRollsBackManualDeleteAndAllInserts() throws Exception {
        service.saveProjectStaffPlans(1, List.of(cell("2026-10", "BE", "", "9")));
        String before = snapshot();
        sql("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_history CHECK(false) NOT VALID");
        assertThatThrownBy(() -> service.saveProjectStaffPlans(1, List.of(cell("2026-10", "BE", "", "2"), cell("2026-11", "BE", "", "2")))).isInstanceOf(RuntimeException.class);
        assertThat(snapshot()).isEqualTo(before);
    }
}
