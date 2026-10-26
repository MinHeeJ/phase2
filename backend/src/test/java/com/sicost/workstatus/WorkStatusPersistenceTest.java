package com.sicost.workstatus;

import static com.sicost.workstatus.WorkStatusModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.mybatis.spring.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WorkStatusPersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    WorkStatusService service;
    WorkStatusMapper mapper;
    Clock clock=Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"),ZoneId.of("Asia/Seoul"));
    @BeforeEach void restoreIsolatedDatabase()throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");assumeTrue(adminUrl!=null&&!adminUrl.isBlank(),"Real PostgreSQL integration requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER");password=System.getenv("TEST_DATABASE_PASSWORD");assumeTrue(user!=null&&password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()){com.sicost.support.TestDatabaseDdl.create(statement, database);}
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);factory.setMapperLocations(new ClassPathResource("mappers/workstatus/WorkStatusMapper.xml"));factory.afterPropertiesSet();
        var template=new SqlSessionTemplate(factory.getObject());mapper=template.getMapper(WorkStatusMapper.class);
        context=new AnnotationConfigApplicationContext();context.register(Transactions.class,WorkStatusService.class,CurrentUser.class,PermissionService.class);
        context.registerBean(WorkStatusMapper.class,()->mapper);context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(Clock.class,()->clock);context.refresh();service=context.getBean(WorkStatusService.class);
        // Each scenario closes only the months it exercises, independently of production seeds.
        sql("UPDATE fin.mth_cls SET cls_stts_cd='OPEN'");
        sql("INSERT INTO mst.dev(dev_id,mng_no,dev_nm,emp_se_cd) VALUES(1,'K-0001','개발자','FT'),(2,'K-0002','다른 개발자','FT')");
    }
    @AfterEach void removeIsolatedDatabase()throws Exception {
        if(context!=null)context.close();if(source==null)return;
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()){com.sicost.support.TestDatabaseDdl.drop(statement, database);}
    }
    long scalar(String sql, Object... args) {return new org.springframework.jdbc.core.JdbcTemplate(source).queryForObject(sql, Long.class, args);}
    String value(String sql, Object... args) {return new org.springframework.jdbc.core.JdbcTemplate(source).queryForObject(sql, String.class, args);}
    void sql(String sql, Object... args) {new org.springframework.jdbc.core.JdbcTemplate(source).update(sql, args);}
    WorkStatusInput input(String code,String start,String end){return new WorkStatusInput(code,LocalDate.parse(start),end==null?null:LocalDate.parse(end),"N","기록");}
    PageResult<WorkStatusRow> list(){return service.list(1,null,new DeveloperPaging(0,50,null));}
    String aggregate()throws Exception {return value("SELECT json_build_object('dev',(SELECT row_to_json(d) FROM mst.dev d WHERE d.dev_id=1),'rows',(SELECT json_agg(h ORDER BY work_stts_hist_id) FROM mst.dev_work_stts_hist h),'audit',(SELECT json_agg(a ORDER BY data_chg_hist_id) FROM sys.data_chg_hist a))::text");}
    void closedMonth(String month)throws Exception {sql("INSERT INTO fin.mth_cls(cls_ym,cls_stts_cd) VALUES(?,'CLOSED') ON CONFLICT(cls_ym) DO UPDATE SET cls_stts_cd='CLOSED'", month);}
    @Test void xmlMaterializesNumericIdsDatesNullAndSharedFilterCount()throws Exception {
        var old=service.create(1,input("VACATION","2026-08-01","2026-08-02"));var recent=service.create(1,input("PARENTAL","2026-10-01",null));
        assertThat(list().items()).extracting(WorkStatusRow::work_stts_hist_id).containsExactly(recent.work_stts_hist_id(),old.work_stts_hist_id());assertThat(list().items().get(0).end_dt()).isNull();
        var filtered=service.list(1,"PARENTAL",new DeveloperPaging(0,20,"bgn_dt,asc"));assertThat(filtered.total()).isEqualTo(1);assertThat(filtered.items()).containsExactly(recent);
        assertThat(service.list(1,"LEAVE",new DeveloperPaging(0,50,null)).items()).isEmpty();assertThat(service.list(2,null,new DeveloperPaging(0,50,null)).items()).isEmpty();
    }
    @Test void parentalCoveringInjectedTodayPersistsWithoutChangingAssignments()throws Exception {
        sql("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cntrct_ty_cd,prj_stts_cd) VALUES(1,'P1','진행','TK','RUN')");
        sql("INSERT INTO asgn.asgn(asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt) VALUES('A1',1,1,'BE','2026-10-01','2026-10-31')");
        String assignments=value("SELECT json_agg(a)::text FROM asgn.asgn a");long before=list().total();
        service.create(1,input("PARENTAL","2026-10-01","2026-10-31"));assertThat(list().total()).isEqualTo(before+1);
        try(var c=source.getConnection();var s=c.prepareStatement("SELECT EXISTS(SELECT 1 FROM mst.dev_work_stts_hist WHERE dev_id=1 AND work_stts_cd='PARENTAL' AND bgn_dt<=? AND (end_dt IS NULL OR end_dt>=?))")) {
            s.setObject(1,LocalDate.now(clock));s.setObject(2,LocalDate.now(clock));try(var r=s.executeQuery()){r.next();assertThat(r.getBoolean(1)).isTrue();}
        }
        assertThat(value("SELECT json_agg(a)::text FROM asgn.asgn a")).isEqualTo(assignments);
    }
    @Test void resignationCreateEditTransitionDeleteAndFullOwnerSnapshots()throws Exception {
        var row=service.create(1,input("RESIGN","2026-10-20","2026-10-31"));assertThat(value("SELECT rsgn_dt::text FROM mst.dev WHERE dev_id=1")).isEqualTo("2026-10-20");assertThat(row.end_dt()).isNull();
        var json=new ObjectMapper();String record=Long.toString(row.work_stts_hist_id());
        var after=json.readTree(value("SELECT aft_val FROM sys.data_chg_hist WHERE obj_rec_id=? AND data_chg_ty_cd='INSERT'", record));assertThat(after.get("dev_id").asLong()).isEqualTo(1);assertThat(after.has("created_at")).isTrue();
        service.update(1,row.work_stts_hist_id(),input("RESIGN","2026-10-21",null));assertThat(value("SELECT rsgn_dt::text FROM mst.dev WHERE dev_id=1")).isEqualTo("2026-10-21");
        service.update(1,row.work_stts_hist_id(),input("LEAVE","2026-10-21","2026-10-31"));assertThat(value("SELECT rsgn_dt::text FROM mst.dev WHERE dev_id=1")).isNull();
        service.update(1,row.work_stts_hist_id(),input("RESIGN","2026-10-22",null));
        var result=service.delete(1,row.work_stts_hist_id());assertThat(result.rsgn_dt()).isNull();assertThat(list().items()).isEmpty();
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='mst.dev_work_stts_hist' AND obj_rec_id=? AND data_chg_ty_cd='DELETE' AND aft_val IS NULL AND (bfr_val::jsonb->>'dev_id')::bigint=1", record)).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE data_chg_ty_cd='UPDATE' AND bfr_val IS NOT NULL AND aft_val IS NOT NULL")).isEqualTo(3);
    }
    @Test void anonymousWorkStatusCrudPersistsResignationAndOwnedAuditSideEffects() throws Exception {
        var json = new ObjectMapper().findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var mvc = MockMvcBuilders.standaloneSetup(new WorkStatusController(service, json))
            .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(json))
            .setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter()).build();
        var response = mvc.perform(post("/api/developers/1/work-status").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(input("RESIGN", "2026-10-20", null))))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.data.dev_id").value(1)).andReturn();
        long id = json.readTree(response.getResponse().getContentAsByteArray()).path("data").path("work_stts_hist_id").asLong();
        assertThat(value("SELECT rsgn_dt::text FROM mst.dev WHERE dev_id=1")).isEqualTo("2026-10-20");
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='mst.dev_work_stts_hist' AND obj_rec_id=? AND data_chg_ty_cd='INSERT'", Long.toString(id))).isEqualTo(1);
        mvc.perform(put("/api/developers/1/work-status/{histId}", id).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(input("RESIGN", "2026-10-21", null))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.bgn_dt").value("2026-10-21"));
        assertThat(value("SELECT rsgn_dt::text FROM mst.dev WHERE dev_id=1")).isEqualTo("2026-10-21");
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='mst.dev_work_stts_hist' AND obj_rec_id=? AND data_chg_ty_cd='UPDATE'", Long.toString(id))).isEqualTo(1);
        mvc.perform(delete("/api/developers/1/work-status/{histId}", id)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.deleted").value(true)).andExpect(jsonPath("$.data.rsgn_dt").isEmpty());
        assertThat(value("SELECT rsgn_dt::text FROM mst.dev WHERE dev_id=1")).isNull();
        assertThat(scalar("SELECT count(*) FROM mst.dev_work_stts_hist WHERE dev_id=1")).isZero();
        assertThat(scalar("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='mst.dev_work_stts_hist' AND obj_rec_id=? AND data_chg_ty_cd='DELETE' AND aft_val IS NULL AND (bfr_val::jsonb->>'dev_id')::bigint=1", Long.toString(id))).isEqualTo(1);
    }
    @Test void updateReusesIdAndDeletePreservesOtherRows()throws Exception {
        var old=service.create(1,input("VACATION","2026-08-01","2026-08-02"));var next=service.create(1,input("LEAVE","2026-10-01","2026-10-02"));
        var changed=service.update(1,next.work_stts_hist_id(),new WorkStatusInput("LEAVE",LocalDate.of(2026,10,2),LocalDate.of(2026,10,3),"Y","수정 메모"));assertThat(changed.work_stts_hist_id()).isEqualTo(next.work_stts_hist_id());assertThat(changed.rmrk_desc()).isEqualTo("수정 메모");assertThat(changed.paid_yn()).isEqualTo("N");
        service.delete(1,next.work_stts_hist_id());assertThat(list().items()).containsExactly(old);
    }
    @Test void overlapIncludesEndpointsAndUnboundedDatesAndExcludesSelf()throws Exception {
        var old=service.create(1,input("VACATION","2026-10-01","2026-10-05"));String before=aggregate();
        assertThatThrownBy(()->service.create(1,input("LEAVE","2026-10-05",null))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("PERIOD_OVERLAP"));assertThat(aggregate()).isEqualTo(before);
        service.update(1,old.work_stts_hist_id(),input("VACATION","2026-10-01","2026-10-05"));
        var open=service.create(1,input("LEAVE","2026-10-06",null));
        assertThatThrownBy(()->service.update(1,old.work_stts_hist_id(),input("VACATION","2026-10-01","2026-10-06"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->service.create(1,input("PARENTAL","2040-01-01","2040-01-02"))).isInstanceOf(ApiException.class);assertThat(list().items()).contains(open);
        service.create(1,input("RESIGN","2026-10-07",null));
    }
    @Test void duplicateResignationAndWrongAggregateDoNotMutate()throws Exception {
        var row=service.create(1,input("RESIGN","2026-10-20",null));String before=aggregate();
        assertThatThrownBy(()->service.create(1,input("RESIGN","2026-10-21",null))).isInstanceOfSatisfying(ApiException.class,e->{assertThat(e.status()).isEqualTo(400);assertThat(e.field()).isEqualTo("work_stts_cd");});
        assertThatThrownBy(()->service.update(2,row.work_stts_hist_id(),input("LEAVE","2026-10-21",null))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status()).isEqualTo(404));
        assertThatThrownBy(()->service.delete(2,row.work_stts_hist_id())).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status()).isEqualTo(404));assertThat(aggregate()).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"UPDATE","DELETE"})
    void closedOldPeriodKeepsEverything(String operation)throws Exception {
        var row=service.create(1,input("RESIGN","2026-09-20",null));closedMonth("2026-09");String before=aggregate();
        assertThatThrownBy(()->{if(operation.equals("UPDATE"))service.update(1,row.work_stts_hist_id(),input("LEAVE","2026-10-01","2026-10-31"));else service.delete(1,row.work_stts_hist_id());})
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("CLOSED_MONTH"));assertThat(aggregate()).isEqualTo(before);
    }
    @Test void closeGuardsBothIntervalsAndFutureOpenEndButResignOnlyStartMonth()throws Exception {
        var row=service.create(1,input("VACATION","2026-08-01","2026-08-02"));closedMonth("2026-09");String before=aggregate();
        assertThatThrownBy(()->service.update(1,row.work_stts_hist_id(),input("VACATION","2026-08-01","2026-09-01"))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("CLOSED_MONTH"));assertThat(aggregate()).isEqualTo(before);
        var unbounded=service.create(2,input("LEAVE","2026-08-01",null));assertThatThrownBy(()->service.delete(2,unbounded.work_stts_hist_id())).isInstanceOf(ApiException.class);
        var resigned=service.create(1,input("RESIGN","2026-08-20",null));service.delete(1,resigned.work_stts_hist_id());
    }
    @Test void createHasNoClosedMonthRestriction()throws Exception {closedMonth("2026-09");var row=service.create(1,input("LEAVE","2026-09-01","2026-09-30"));assertThat(list().items()).containsExactly(row);}
    @ParameterizedTest @ValueSource(strings={"INSERT","UPDATE","DELETE"})
    void auditFailureRollsBackHistoryAndResignationTogether(String operation)throws Exception {
        WorkStatusRow row=operation.equals("INSERT")?null:service.create(1,input("RESIGN","2026-10-20",null));String before=aggregate();
        sql("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_history CHECK(false) NOT VALID");
        assertThatThrownBy(()->{switch(operation){case "INSERT"->service.create(1,input("RESIGN","2026-10-21",null));case "UPDATE"->service.update(1,row.work_stts_hist_id(),input("RESIGN","2026-10-21",null));default->service.delete(1,row.work_stts_hist_id());}}).isInstanceOf(RuntimeException.class);
        assertThat(aggregate()).isEqualTo(before);
    }
}
