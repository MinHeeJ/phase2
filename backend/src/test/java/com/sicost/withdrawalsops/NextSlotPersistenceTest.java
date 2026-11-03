package com.sicost.withdrawalsops;

import com.sicost.common.AssignmentTestSupport;
import com.sicost.common.api.*;
import com.sicost.us1.WithdrawalMapper;
import java.sql.*;
import java.time.Clock;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class NextSlotPersistenceTest {
    String adminUrl, user, password, database;
    DriverManagerDataSource source;
    JdbcTemplate jdbc;
    AssignmentTestSupport fixtures;
    AnnotationConfigApplicationContext context;
    MockMvc mvc;
    long project, developer, assignment, customer;
    @BeforeEach void isolatedDatabase() throws Exception {
        adminUrl = System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl != null && !adminUrl.isBlank(), "Real PostgreSQL requires TEST_DATABASE_URL");
        user = System.getenv("TEST_DATABASE_USER"); password = System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user != null && password != null, "Test database connection settings are absent");
        database = "sicost_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var c = DriverManager.getConnection(adminUrl,user,password); var s = c.createStatement()) {
            com.sicost.support.TestDatabaseDdl.create(s,database);
        }
        source = new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        jdbc = new JdbcTemplate(source);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        fixtures = new AssignmentTestSupport(source);
        project = fixtures.createProject(); developer = fixtures.createDeveloper();
        customer = jdbc.queryForObject("INSERT INTO mst.ptnr(ptnr_no,ptnr_se_cd,ptnr_nm,biz_reg_no) VALUES(?, 'CUST','추천 시험 고객','123-45-67890') RETURNING ptnr_id",Long.class,"T-"+UUID.randomUUID().toString().substring(0,12));
        jdbc.update("UPDATE prj.prj SET cust_ptnr_id=? WHERE prj_id=?",customer,project);
        jdbc.update("UPDATE mst.dev SET grd_cd='SR' WHERE dev_id=?",developer);
        assignment = jdbc.queryForObject("INSERT INTO asgn.asgn(asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt) VALUES(?,?,?,'BE',DATE '2026-10-14',DATE '2026-12-31') RETURNING asgn_id",Long.class,"T-"+UUID.randomUUID().toString().substring(0,12),project,developer);
        var factory = new SqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/us1/WithdrawalMapper.xml"),new ClassPathResource("mappers/withdrawals/NextSlotMapper.xml"));
        factory.afterPropertiesSet(); var session = new SqlSessionTemplate(factory.getObject());
        context = new AnnotationConfigApplicationContext();
        context.register(AssignmentTestSupport.Transactions.class,NextSlotService.class);
        context.registerBean(NextSlotMapper.class,()->session.getMapper(NextSlotMapper.class));
        context.registerBean(WithdrawalMapper.class,()->session.getMapper(WithdrawalMapper.class));
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(Clock.class,()->AssignmentTestSupport.CLOCK); context.refresh();
        mvc = MockMvcBuilders.standaloneSetup(new NextSlotController(context.getBean(NextSlotService.class)))
            .setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter()).build();
    }
    @AfterEach void removeDatabase() throws Exception {
        if(context!=null)context.close(); if(fixtures!=null)fixtures.close(); if(source==null)return;
        try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()) {
            com.sicost.support.TestDatabaseDdl.drop(s,database);
        }
    }
    long need(String status, String month, int months, int capacity) {
        long opportunity = jdbc.queryForObject("INSERT INTO bsn.oppt(oppt_no,oppt_nm,cust_ptnr_id,oppt_stts_cd,win_prblt_rt) VALUES(?,'추천 시험 기회',?,?,60) RETURNING oppt_id",Long.class,"T-"+UUID.randomUUID().toString().substring(0,12),customer,status);
        return jdbc.queryForObject("INSERT INTO bsn.oppt_need(oppt_id,job_cd,grd_cd,psn_cnt,bgn_ym,mnth_qty) VALUES(?,'BE','SR',?,?,?) RETURNING need_id",Long.class,opportunity,capacity,month,months);
    }
    Map<String,String> snapshot() {
        var result = new LinkedHashMap<String,String>();
        for(String table:List.of("asgn.asgn","asgn.asgn_mth","asgn.asgn_chg_hist","asgn.tmpr_asgn","bsn.oppt","bsn.oppt_need",
                "cst.mth_cst","cst.cst_aloc","fin.mth_cls","fin.mth_pl","sys.data_chg_hist")) {
            result.put(table,jdbc.queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM "+table+" t",String.class));
        }
        return result;
    }
    @Test void realSqlMaterializesFiltersAndReadOnlySnapshots() throws Exception {
        long valid=need("OPEN","2027-01",3,2);
        var excluded = new ArrayList<Long>();
        for(String status:List.of("WON","LOST","DELETED"))excluded.add(need(status,"2027-01",3,1));
        excluded.add(need("OPEN","2027-04",3,1)); excluded.add(need("OPEN","2026-01",1,1));
        long inactive=need("OPEN","2027-01",3,1); jdbc.update("UPDATE bsn.oppt_need SET use_yn='N' WHERE need_id=?",inactive); excluded.add(inactive);
        long full=need("OPEN","2027-01",3,1);
        jdbc.update("INSERT INTO asgn.tmpr_asgn(need_id,dev_id,bgn_ym,end_ym,tmpr_asgn_stts_cd) VALUES(?,?,'2027-01','2027-03','ACTIVE')",full,developer); excluded.add(full);
        jdbc.update("INSERT INTO asgn.tmpr_asgn(need_id,dev_id,bgn_ym,end_ym,tmpr_asgn_stts_cd) VALUES(?,?,'2027-01','2027-03','RELEASED')",valid,developer);
        var before=snapshot();
        var data=context.getBean(NextSlotService.class).get(assignment);
        assertThat(data.items()).extracting(NextSlotModels.NextSlot::need_id).contains(valid).doesNotContainAnyElementsOf(excluded);
        var found=data.items().stream().filter(n->n.need_id()==valid).findFirst().orElseThrow();
        assertThat(found.remaining_slots()).isEqualTo(2); assertThat(found.mtch_scr()).isEqualTo(100);
        assertThat(data.next_temp_assignments()).hasSize(1);
        mvc.perform(get("/api/withdrawals/{id}/next-slots",assignment)).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        assertThat(context.getBean(NextSlotService.class).get(assignment)).isEqualTo(data);
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void invalidReadLeavesSourcesClosedAndHistoryUnchanged() throws Exception {
        need("OPEN","2027-01",3,1); var before=snapshot();
        mvc.perform(get("/api/withdrawals/{id}/next-slots",assignment).param("month","2026-13"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("month"))
            .andExpect(jsonPath("$.message").isNotEmpty()).andExpect(jsonPath("$.requestId").isNotEmpty());
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void confirmedNextAssignmentExcludesRecommendationWithoutHidingTemporary() throws Exception {
        need("OPEN","2027-01",3,1);
        jdbc.update("INSERT INTO asgn.asgn(asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt) VALUES(?,?,?,'BE',DATE '2027-01-01',DATE '2027-03-31')","T-"+UUID.randomUUID().toString().substring(0,12),project,developer);
        assertThat(context.getBean(NextSlotService.class).get(assignment).items()).isEmpty();
    }
}
