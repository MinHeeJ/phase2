package com.sicost.assignmentsops;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.sicost.common.AssignmentTestSupport;
import com.sicost.common.api.*;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.persistence.*;
import com.sicost.common.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AssignmentOperationPersistenceTest {
    String adminUrl, user, password, database;
    DriverManagerDataSource source;
    AssignmentTestSupport fixtures;
    AnnotationConfigApplicationContext context;
    AssignmentOperationMapper mapper;
    MockMvc mvc;
    long project, developer, incoming;
    @BeforeEach void isolatedDatabase() throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl!=null&&!adminUrl.isBlank(),"Real PostgreSQL requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER"); password=System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user!=null&&password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()) {
            com.sicost.support.TestDatabaseDdl.create(s,database);
        }
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        fixtures=new AssignmentTestSupport(source);
        project=fixtures.createProject(); developer=fixtures.createDeveloper(); incoming=fixtures.createDeveloper();
        var factory=new SqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/assignmentsops/AssignmentOperationMapper.xml"),
            new ClassPathResource("mappers/common/AssignmentConcurrencyMapper.xml"));
        factory.afterPropertiesSet(); var session=new SqlSessionTemplate(factory.getObject());
        mapper=session.getMapper(AssignmentOperationMapper.class);
        context=new AnnotationConfigApplicationContext();
        context.register(AssignmentTestSupport.Transactions.class, AssignmentOperationService.class,
            AssignmentExportService.class, ExcelExportService.class, AssignmentConcurrencyService.class,
            CurrentUser.class, PermissionService.class);
        context.registerBean(AssignmentOperationMapper.class,()->mapper);
        context.registerBean(AssignmentConcurrencyMapper.class,()->session.getMapper(AssignmentConcurrencyMapper.class));
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(Clock.class,()->AssignmentTestSupport.CLOCK);
        context.refresh();
        mvc=MockMvcBuilders.standaloneSetup(new AssignmentOperationController(context.getBean(AssignmentOperationService.class),
            context.getBean(AssignmentExportService.class),fixtures.json))
            .setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter()).build();
    }
    @AfterEach void cleanup() throws Exception {
        if(context!=null) context.close(); if(fixtures!=null) fixtures.close(); if(source==null) return;
        try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()) {
            com.sicost.support.TestDatabaseDdl.drop(s,database);
        }
    }
    void sql(String sql) throws Exception { try(var c=source.getConnection();var s=c.createStatement()){s.execute(sql);} }
    String value(String sql) throws Exception {
        try(var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery(sql)){r.next(); return r.getString(1);}
    }
    long assignment(long person, String start, String end) throws Exception {
        long id=Long.parseLong(value("SELECT nextval('asgn.seq_asgn_id')"));
        sql("INSERT INTO asgn.asgn(asgn_id,asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt,plan_end_dt,ext_psblty_cd,updated_at) VALUES("+
            id+",'A-"+String.format("%04d",id)+"',"+project+","+person+",'BE','"+start+"','"+end+"','"+end+"','M','2026-10-01 00:00:00.123456')");
        sql("INSERT INTO asgn.untprc_hist(asgn_id,aply_bgn_dt,bill_untprc_amt) VALUES("+id+",'"+start+"',7000000)");
        sql("INSERT INTO asgn.asgn_mth(asgn_id,work_ym,mm_qty,auto_mm_qty) VALUES("+id+",'2026-11',1,1)");
        sql("INSERT INTO asgn.asgn_chg_hist(asgn_id,asgn_chg_ty_cd,aply_dt,aft_val) VALUES("+id+",'START','"+start+"','등록')");
        return id;
    }
    Map<String,Object> batch(long... ids) throws Exception {
        List<Long> keys=new ArrayList<>(); List<Map<String,Object>> versions=new ArrayList<>();
        for(long id:ids) {
            keys.add(id); var row=mapper.row(new AssignmentOperationModels.ReadKey(id,LocalDate.of(2026,10,14),2026));
            versions.add(Map.of("asgn_id",id,"updated_at",row.updated_at().toString()));
        }
        var body=new LinkedHashMap<String,Object>(); body.put("ids",keys); body.put("versions",versions); return body;
    }
    String snapshot() throws Exception {
        return value("SELECT json_build_object('a',(SELECT json_agg(a ORDER BY asgn_id) FROM asgn.asgn a),'m',(SELECT json_agg(m ORDER BY asgn_id,work_ym) FROM asgn.asgn_mth m),'r',(SELECT json_agg(r ORDER BY asgn_id,aply_bgn_dt) FROM asgn.untprc_hist r),'h',(SELECT json_agg(h ORDER BY asgn_chg_id) FROM asgn.asgn_chg_hist h))::text");
    }
    @Test void cancellationPersistsAndPreservesHistory() throws Exception {
        long id=assignment(developer,"2026-11-01","2026-12-31");
        String history=value("SELECT json_agg(h ORDER BY asgn_chg_id)::text FROM asgn.asgn_chg_hist h");
        mvc.perform(post("/api/assignments/cancel").contentType("application/json").content(fixtures.json.writeValueAsBytes(batch(id))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].status").value("APPLIED"));
        assertThat(value("SELECT asgn_stts_cd FROM asgn.asgn WHERE asgn_id="+id)).isEqualTo("CANCELLED");
        assertThat(value("SELECT count(*) FROM asgn.asgn_mth WHERE asgn_id="+id)).isEqualTo("0");
        assertThat(value("SELECT json_agg(h ORDER BY asgn_chg_id)::text FROM asgn.asgn_chg_hist h")).isEqualTo(history);
    }
    @Test void closedCancellationConflictAndMixedExclusion() throws Exception {
        long blocked=assignment(developer,"2026-09-01","2026-12-31");
        sql("INSERT INTO asgn.asgn_mth(asgn_id,work_ym,mm_qty) VALUES("+blocked+",'2026-09',1)");
        String before=snapshot();
        mvc.perform(post("/api/assignments/cancel").contentType("application/json").content(fixtures.json.writeValueAsBytes(batch(blocked))))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ASSIGNMENT_IN_USE"));
        assertThat(snapshot()).isEqualTo(before);
        long eligible=assignment(incoming,"2026-11-01","2026-12-31");
        mvc.perform(post("/api/assignments/cancel").contentType("application/json").content(fixtures.json.writeValueAsBytes(batch(blocked,eligible))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].status").value("EXCLUDED"))
            .andExpect(jsonPath("$.data.items[1].status").value("APPLIED"));
    }
    @Test void chanceHistoryAndEqualSkip() throws Exception {
        long id=assignment(developer,"2026-10-01","2026-12-31");
        var request=batch(id); request.put("value","H");
        mvc.perform(post("/api/assignments/ext-chance").contentType("application/json").content(fixtures.json.writeValueAsBytes(request)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].status").value("APPLIED"));
        assertThat(value("SELECT count(*) FROM asgn.asgn_chg_hist WHERE asgn_chg_ty_cd='EXT_CHANCE'")).isEqualTo("1");
        request=batch(id); request.put("value","H"); String before=snapshot();
        mvc.perform(post("/api/assignments/ext-chance").contentType("application/json").content(fixtures.json.writeValueAsBytes(request)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].status").value("SKIPPED"));
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void chanceHistoryFailureRollsBackParent() throws Exception {
        long id=assignment(developer,"2026-10-01","2026-12-31");
        var request=batch(id); request.put("value","H"); String before=snapshot();
        sql("ALTER TABLE asgn.asgn_chg_hist ADD CONSTRAINT reject_history CHECK(false) NOT VALID");
        assertThatThrownBy(()->context.getBean(AssignmentOperationService.class).chance(
            AssignmentOperationInput.batch(fixtures.json.valueToTree(request),true))).isInstanceOf(RuntimeException.class);
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void replacementInheritsFutureRatesAndTwoLinkedHistories() throws Exception {
        long id=assignment(developer,"2026-10-01","2026-12-31");
        sql("INSERT INTO asgn.untprc_hist(asgn_id,aply_bgn_dt,bill_untprc_amt) VALUES("+id+",'2026-12-01',7200000)");
        var result=context.getBean(AssignmentOperationService.class).replace(id,
            new AssignmentOperationModels.ReplacementRequest(mapper.row(new AssignmentOperationModels.ReadKey(id,LocalDate.of(2026,10,14),2026)).updated_at(),
                LocalDate.of(2026,11,16),incoming,"PERSON",null));
        long next=result.new_assignment().row().asgn_id();
        assertThat(result.previous_assignment().row().end_dt()).isEqualTo(LocalDate.of(2026,11,15));
        assertThat(result.previous_assignment().row().asgn_stts_cd()).isEqualTo("REPLACED");
        assertThat(result.new_assignment().row().cntrct_dev_id()).isEqualTo(developer);
        assertThat(result.new_assignment().row().rplc_asgn_id()).isEqualTo(id);
        assertThat(mapper.rates(next)).extracting(AssignmentOperationModels.Rate::bill_untprc_amt).containsExactly(7000000L,7200000L);
        assertThat(result.changes()).hasSize(2);
        assertThat(value("SELECT new_asgn_id FROM asgn.asgn_chg_hist WHERE asgn_id="+id+" AND asgn_chg_ty_cd='REPLACE'")).isEqualTo(Long.toString(next));
        assertThat(value("SELECT mm_qty FROM asgn.asgn_mth WHERE asgn_id="+id+" AND work_ym='2026-11'")).isEqualTo("0.50");
    }
    @Test void replacementMmFailureRollsBackBothAssignmentsAndHistory() throws Exception {
        long id=assignment(developer,"2026-10-01","2026-12-31"); String before=snapshot();
        sql("ALTER TABLE asgn.asgn_mth ADD CONSTRAINT reject_new_month CHECK(false) NOT VALID");
        var version=mapper.row(new AssignmentOperationModels.ReadKey(id,LocalDate.of(2026,10,14),2026)).updated_at();
        assertThatThrownBy(()->context.getBean(AssignmentOperationService.class).replace(id,
            new AssignmentOperationModels.ReplacementRequest(version,LocalDate.of(2026,11,16),incoming,"PERSON",null)))
            .isInstanceOf(RuntimeException.class);
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void previewAndExportLeaveEveryBusinessTableUnchanged() throws Exception {
        long id=assignment(developer,"2026-11-01","2026-12-31"); String before=snapshot();
        var input=Map.of("prj_id",project,"dev_id",incoming,"job_cd","BE","bgn_dt","2026-11-16",
            "end_dt","2026-12-31","asgn_rt",1,"asgn_se_cd","NEW");
        mvc.perform(post("/api/assignments/check").contentType("application/json").content(fixtures.json.writeValueAsBytes(input)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.months[0].auto_mm_qty").value(0.50));
        mvc.perform(get("/api/assignments/export").param("year","2026").param("projectId",Long.toString(project)).param("job","BE"))
            .andExpect(status().isOk());
        assertThat(snapshot()).isEqualTo(before);
        assertThat(mapper.rates(id)).hasSize(1);
    }
}
