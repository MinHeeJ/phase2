package com.sicost.projectsops;

import static com.sicost.projectsops.ProjectLifecycleModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.sicost.common.api.*;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.security.*;
import com.sicost.projects.ProjectMapper;
import com.sicost.projects.ProjectModels.ProjectListItem;
import com.sicost.projects.ProjectQuery;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.mybatis.spring.*;

class ProjectLifecyclePersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    ProjectLifecycleService service;
    ProjectLifecycleMapper mapper;
    org.springframework.test.web.servlet.MockMvc mvc;
    Clock clock=Clock.fixed(Instant.parse("2026-10-22T15:01:00Z"),ZoneId.of("Asia/Seoul"));
    @BeforeEach void restoreIsolatedDatabase()throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");assumeTrue(adminUrl!=null&&!adminUrl.isBlank(),"Real PostgreSQL integration requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER");password=System.getenv("TEST_DATABASE_PASSWORD");assumeTrue(user!=null&&password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var s=admin.createStatement()){com.sicost.support.TestDatabaseDdl.create(s, database);}
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);factory.setMapperLocations(new ClassPathResource("mappers/projectsops/ProjectLifecycleMapper.xml"));factory.afterPropertiesSet();
        mapper=new SqlSessionTemplate(factory.getObject()).getMapper(ProjectLifecycleMapper.class);
        context=new AnnotationConfigApplicationContext();context.register(Transactions.class,ProjectLifecycleService.class,CurrentUser.class,PermissionService.class,ExcelExportService.class);
        context.registerBean(ProjectLifecycleMapper.class,()->mapper);context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));context.registerBean(Clock.class,()->clock);context.refresh();service=context.getBean(ProjectLifecycleService.class);
        mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new ProjectLifecycleController(service))
            .setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter()).build();
        sql("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cntrct_ty_cd,prj_stts_cd,cntrct_bgn_dt,cntrct_end_dt) VALUES(1,'P-2601','프로젝트','TK','RUN','2026-01-01','2026-12-31'),(2,'P-2602','종료','TK','END','2026-01-01','2026-12-31'),(3,'P-2603','실주','TK','LOST','2026-01-01','2026-12-31')");
        sql("INSERT INTO mst.dev(dev_id,mng_no,dev_nm,emp_se_cd) VALUES(1,'K-0001','개발자','FT')");
    }
    @AfterEach void removeDatabase()throws Exception{if(context!=null)context.close();if(source==null)return;try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()){com.sicost.support.TestDatabaseDdl.drop(s, database);}}
    void sql(String sql, Object... args){new org.springframework.jdbc.core.JdbcTemplate(source).update(sql, args);}
    String value(String sql)throws Exception{try(var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery(sql)){assertThat(r.next()).isTrue();return r.getString(1);}}
    String snapshot()throws Exception{return value("SELECT json_build_object('projects',(SELECT json_agg(p ORDER BY prj_id) FROM prj.prj p),'audit',(SELECT json_agg(h ORDER BY data_chg_hist_id) FROM sys.data_chg_hist h))::text");}
    void assignment(String status)throws Exception{sql("INSERT INTO asgn.asgn(asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt,asgn_stts_cd) VALUES('A-0001',1,1,'BE','2025-01-01','2025-12-31',?)", status);}
    @Test void lifecyclePreservesBusinessRecordsAndNoopAndAudits()throws Exception{
        assignment("CANCELLED");String records=value("SELECT json_agg(a)::text FROM asgn.asgn a");
        mvc.perform(post("/api/projects/1/disable")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.use_yn").value("N"));assertThat(service.options(null)).isEmpty();
        assertThat(service.history(1)).extracting(ProjectHistoryEntry::field).containsExactly("use_yn");
        assertThat(value("SELECT data_chg_ty_cd FROM sys.data_chg_hist")).isEqualTo("DISABLE");String after=snapshot();service.disable(1);assertThat(snapshot()).isEqualTo(after);
        mvc.perform(post("/api/projects/1/enable")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.use_yn").value("Y"));assertThat(service.options(null)).extracting(ProjectOption::id).containsExactly(1L);
        assertThat(value("SELECT json_agg(a)::text FROM asgn.asgn a")).isEqualTo(records);
        assertThat(value("SELECT count(*) FROM sys.data_chg_hist WHERE data_chg_ty_cd='UPDATE' AND clmn_nm='use_yn' AND bfr_val='N' AND aft_val='Y' AND chg_user_id IS NULL")).isEqualTo("1");
        after=snapshot();service.enable(1);assertThat(snapshot()).isEqualTo(after);
    }
    @ParameterizedTest @ValueSource(strings={"assignment","bill","allocation","expense"}) void eachResourceBlocksAndPreserves(String kind)throws Exception{
        switch(kind){
            case "assignment" -> assignment("ENDED");
            case "bill" -> sql("INSERT INTO prj.bill_stg(prj_id,stg_odr_no,bill_stg_nm,sply_amt) VALUES(1,1,'착수',100)");
            case "allocation" -> sql("INSERT INTO prj.tk_aloc_line(prj_id,aloc_stg_cd,line_ty_cd,item_nm) VALUES(1,'EST','ITEM','경비')");
            case "expense" -> sql("INSERT INTO cst.drct_expns(expns_ym,prj_id,expns_item_cd,expns_amt) VALUES('2026-10',1,'TRIP',100)");
        }
        String before=snapshot();assertThatThrownBy(()->service.disable(1)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("PROJECT_IN_USE"));assertThat(snapshot()).isEqualTo(before);
    }
    @Test void assignmentsAreUnpagedCancelledExcludedAndMaterializeSixFields()throws Exception{
        assignment("ENDED");sql("INSERT INTO asgn.asgn(asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt,asgn_stts_cd) VALUES('A-0002',1,1,'BE','2026-01-01','2026-12-31','CANCELLED')");
        var rows=service.assignments(1);assertThat(rows).hasSize(1);assertThat(rows.get(0).name()).isEqualTo("개발자");assertThat(rows.get(0).beginDate()).isEqualTo(LocalDate.of(2025,1,1));assertThat(rows.get(0).assignmentRate()).isEqualByComparingTo("1.00");
    }
    @Test void historyIncludesOnlyProjectTablesAndSameProjectId()throws Exception{
        sql("INSERT INTO sys.data_chg_hist(tbl_nm,obj_rec_id,data_chg_ty_cd,aft_val) VALUES('prj.prj','1','INSERT','프로젝트'),('prj.bill_stg','1','UPDATE','청구'),('mst.dev','1','UPDATE','개발자'),('prj.prj','2','UPDATE','다른 프로젝트')");
        assertThat(service.history(1)).extracting(ProjectHistoryEntry::after).containsExactly("청구","프로젝트");assertThat(service.history(1)).extracting(ProjectHistoryEntry::actor).containsOnly("기본 사용자");
    }
    @Test void optionsSearchExcludesDisabledAndClosedProjectsAndNullableCustomer()throws Exception{
        assertThat(service.options("P-2601")).extracting(ProjectOption::id).containsExactly(1L);assertThat(service.options("없는 검색")).isEmpty();assertThat(service.options(null).get(0).custName()).isNull();
        sql("UPDATE prj.prj SET use_yn='N' WHERE prj_id=1");assertThat(service.options(null)).isEmpty();service.enable(2);assertThat(service.options(null)).isEmpty();
    }
    @Test void exportFiltersPerformanceIntersectionSortAndNoLimit()throws Exception{
        sql("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cntrct_ty_cd,prj_stts_cd,cntrct_bgn_dt,cntrct_end_dt) SELECT 3+n,'P-'||(2700+n),'데이터'||n,'MO','RUN','2025-12-01','2026-01-01' FROM generate_series(1,101) n");
        var q=service.exportQuery(Map.of("year","2026","type","MO","part","NONE","sort","prj_nm,asc"));
        var rows=mapper.exportRows(q);assertThat(rows).hasSize(101);assertThat(rows.get(0)).containsKeys("assignmentCount","custName","partName");
        assertThat(mapper.exportRows(service.exportQuery(Map.of("year","2027")))).isEmpty();assertThat(mapper.exportRows(service.exportQuery(Map.of("year","2026","status","END")))).hasSize(1);
    }
    @Test void auditFailureRollsBackUseFlag()throws Exception{
        sql("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_project_audit CHECK(tbl_nm!='prj.prj')");
        assertThatThrownBy(()->service.disable(1)).isInstanceOf(RuntimeException.class);assertThat(value("SELECT use_yn FROM prj.prj WHERE prj_id=1")).isEqualTo("Y");
    }
    ProjectMapper projectMapper() throws Exception {
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/projects/ProjectMapper.xml"));
        factory.afterPropertiesSet();
        return new SqlSessionTemplate(factory.getObject()).getMapper(ProjectMapper.class);
    }
    @Test void exportAndListKeepTheSameProjectsWhenTheirPartIsDisabled() throws Exception {
        sql("UPDATE prj.prj SET bsn_part_id=1 WHERE prj_id=1");
        sql("UPDATE mst.bsn_part SET use_yn='N' WHERE bsn_part_id=1");
        var filters = Map.of("year", "2026", "part", "1");
        var listed = projectMapper().list(ProjectQuery.of(filters, LocalDate.now(clock)));
        var exported = mapper.exportRows(service.exportQuery(filters));
        assertThat(listed).extracting(ProjectListItem::prj_id)
            .containsExactly(1L);
        assertThat(exported).extracting(row -> ((Number) row.get("prj_id")).longValue())
            .containsExactlyElementsOf(listed.stream().map(ProjectListItem::prj_id).toList());
    }
    @Test void lifecycleAndNormalDetailUseTheSameHeadquartersLabel() throws Exception {
        sql("UPDATE prj.prj SET bsn_part_id=1 WHERE prj_id=1");
        sql("UPDATE mst.bsn_part SET bsn_part_nm='관리상 본사명' WHERE bsn_part_id=1");
        var detail = projectMapper().row(
            new com.sicost.projects.ProjectModels.ProjectRead(1, LocalDate.now(clock)));
        assertThat(detail.partName()).isEqualTo("본사(직접 영업)");
        assertThat(service.disable(1)).containsEntry("partName", detail.partName());
        assertThat(service.enable(1)).containsEntry("partName", detail.partName());
    }
}
