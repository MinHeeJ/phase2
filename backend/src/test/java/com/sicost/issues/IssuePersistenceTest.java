package com.sicost.issues;

import static com.sicost.issues.IssueModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.*;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.mybatis.spring.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class IssuePersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    IssueService service;
    IssueMapper mapper;
    MockMvc mvc;
    ObjectMapper json;
    @BeforeEach void restoreIsolatedDatabase()throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl!=null&&!adminUrl.isBlank(),"Real PostgreSQL requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER");password=System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user!=null&&password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()) {com.sicost.support.TestDatabaseDdl.create(s,database);}
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/issues/IssueMapper.xml"));factory.afterPropertiesSet();
        mapper=new SqlSessionTemplate(factory.getObject()).getMapper(IssueMapper.class);
        context=new AnnotationConfigApplicationContext();context.register(Transactions.class,IssueService.class,CurrentUser.class,PermissionService.class);
        context.registerBean(IssueMapper.class,()->mapper);
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(Clock.class,()->Clock.fixed(Instant.parse("2026-10-22T15:01:00Z"),ZoneId.of("Asia/Seoul")));
        context.refresh();service=context.getBean(IssueService.class);
        json=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mvc=MockMvcBuilders.standaloneSetup(new IssueController(service,json)).setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter()).build();
        sql("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cntrct_ty_cd,prj_stts_cd) VALUES(1,'P-2601','이슈 시험','TK','RUN'),(2,'P-2602','보류 시험','TK','HOLD'),(3,'P-2603','종료 시험','TK','END'),(4,'P-2604','실주 시험','TK','LOST'),(5,'P-2605','중지 시험','TK','RUN')");
        sql("UPDATE prj.prj SET use_yn='N' WHERE prj_id=5");
    }
    @AfterEach void removeDatabase()throws Exception {
        if(context!=null)context.close();if(source==null)return;
        try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()){com.sicost.support.TestDatabaseDdl.drop(s,database);}
    }
    void sql(String sql)throws Exception {try(var c=source.getConnection();var s=c.createStatement()){s.execute(sql);}}
    String value(String sql)throws Exception {try(var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery(sql)){r.next();return r.getString(1);}}
    String snapshot()throws Exception {return value("SELECT json_build_object('issues',(SELECT json_agg(i ORDER BY issue_id) FROM prj.issue i),'history',(SELECT json_agg(h ORDER BY data_chg_hist_id) FROM sys.data_chg_hist h))::text");}
    Map<String,Object> input(Long project,String title,String state) {
        var b=new LinkedHashMap<String,Object>();if(project!=null)b.put("prj_id",project);
        b.put("ttl_nm",title);b.put("svrt_cd","MID");b.put("ddln_dt","2026-10-01");b.put("issue_stts_cd",state);b.put("chrg_nm","외부 담당자");return b;
    }
    long create(long project,String state)throws Exception {
        var response=mvc.perform(post("/api/issues").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input(project,"검토",state))))
            .andExpect(status().isOk()).andReturn();return json.readTree(response.getResponse().getContentAsByteArray()).path("data").path("issue_id").asLong();
    }
    @Test void anonymousRunCreateReadbackOpenCountDatesAndOwner()throws Exception {
        long id=create(1,"OPEN");create(2,"DOING");
        assertThat(value("SELECT count(*) FROM prj.issue WHERE chrg_user_id IS NULL AND end_dt IS NULL AND reg_dt='2026-10-23'")).isEqualTo("2");
        assertThat(mapper.row(id).ttl_nm()).isEqualTo("검토");
        mvc.perform(get("/api/issues")).andExpect(status().isOk()).andExpect(jsonPath("$.data.open_count").value(2));
        assertThat(value("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='prj.issue'")).isEqualTo("0");
    }
    @Test void closedCreateKeepsOpenCountAndSetsBothDates()throws Exception {
        create(1,"CLOSED");assertThat(value("SELECT count(*) FROM prj.issue WHERE reg_dt='2026-10-23' AND end_dt='2026-10-23' AND chrg_user_id IS NULL")).isEqualTo("1");
        assertThat(service.list(Map.of()).open_count()).isZero();
    }
    @ParameterizedTest @CsvSource({"prj_id,프로젝트를 골라 주세요.","ttl_nm,제목을 넣어 주세요.","ddln_dt,기한을 넣어 주세요."})
    void invalidCreateDoesNotChangeDatabase(String field,String message)throws Exception {
        var b=input(1L,"검토","OPEN");b.remove(field);String before=snapshot();
        mvc.perform(post("/api/issues").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(b))).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.field").value(field)).andExpect(jsonPath("$.message").value(message));assertThat(snapshot()).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(longs={3,4,5})
    void closedParentPreventsInsert(long parent)throws Exception {
        String before=snapshot();mvc.perform(post("/api/issues").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input(parent,"검토","OPEN"))))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PROJECT_CLOSED"));assertThat(snapshot()).isEqualTo(before);
    }
    @Test void updateRecordsExactChangedColumnsAndReopenClearsEnd()throws Exception {
        long id=create(1,"OPEN");var b=input(null,"새 제목","CLOSED");b.put("actn_desc","조치 완료");
        mvc.perform(put("/api/issues/{id}",id).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(b))).andExpect(status().isOk());
        assertThat(mapper.row(id).prj_id()).isEqualTo(1);assertThat(mapper.row(id).ttl_nm()).isEqualTo("새 제목");
        assertThat(value("SELECT bfr_val||'>'||aft_val FROM sys.data_chg_hist WHERE tbl_nm='prj.issue' AND clmn_nm='ttl_nm' AND obj_rec_id='"+id+"'")).isEqualTo("검토>새 제목");
        assertThat(value("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='prj.issue' AND obj_rec_id='"+id+"'")).isEqualTo("4");
        assertThat(mapper.row(id).end_dt()).isEqualTo(LocalDate.of(2026,10,23));
        String before=snapshot();service.update(id,IssueInputParser.update(json.valueToTree(b)));assertThat(snapshot()).isEqualTo(before);
        b.put("issue_stts_cd","OPEN");service.update(id,IssueInputParser.update(json.valueToTree(b)));
        assertThat(mapper.row(id).end_dt()).isNull();assertThat(service.list(Map.of()).open_count()).isEqualTo(1);
        assertThat(value("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='prj.issue' AND clmn_nm='end_dt' AND bfr_val='2026-10-23' AND aft_val IS NULL")).isEqualTo("1");
    }
    @Test void badTitleUpdateLeavesOriginalAndHistory()throws Exception {
        long id=create(1,"OPEN");String before=snapshot();
        mvc.perform(put("/api/issues/{id}",id).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input(null,"","OPEN"))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("ttl_nm"));assertThat(snapshot()).isEqualTo(before);
    }
    @Test void endParentUpdateLeavesIssueAndHistory()throws Exception {
        long id=create(1,"OPEN");sql("UPDATE prj.prj SET prj_stts_cd='END' WHERE prj_id=1");String before=snapshot();
        mvc.perform(put("/api/issues/{id}",id).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input(null,"새 제목","CLOSED"))))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PROJECT_CLOSED"));assertThat(snapshot()).isEqualTo(before);
    }
    @Test void historyFailureRollsBackRowAndAllHistory()throws Exception {
        long id=create(1,"OPEN");String before=snapshot();sql("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_issue_history CHECK(false) NOT VALID");
        assertThatThrownBy(()->service.update(id,IssueInputParser.update(json.valueToTree(input(null,"새 제목","CLOSED"))))).isInstanceOf(RuntimeException.class);
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void allStatusFiltersAlwaysExcludeClosedLostDisabledParentsAndCountMatches()throws Exception {
        for(int project=1;project<=5;project++) for(String state:List.of("OPEN","DOING","CLOSED"))
            sql("INSERT INTO prj.issue(prj_id,ttl_nm,svrt_cd,ddln_dt,issue_stts_cd,reg_dt) VALUES("+project+",'조건','HIGH','2026-10-01','"+state+"','2026-10-01')");
        for(String status:List.of("OPEN_DOING","OVERDUE","CLOSED")) {
            var data=service.list(Map.of("status",status));assertThat(data.total()).isEqualTo(data.items().size());
            assertThat(data.items()).allMatch(r->r.prj_id()<=2);assertThat(data.open_count()).isEqualTo(4);
        }
        var filtered=service.list(Map.of("severity","HIGH","projectId","2"));assertThat(filtered.total()).isEqualTo(2);
        assertThat(filtered.items()).allMatch(r->r.prj_id()==2&&r.svrt_cd().equals("HIGH"));
        assertThat(service.list(Map.of("severity","LOW")).total()).isZero();
    }
    @Test void sortingBeforePaginationAndTodayIsNotOverdue()throws Exception {
        for(int i=1;i<=55;i++)sql("INSERT INTO prj.issue(prj_id,ttl_nm,svrt_cd,ddln_dt,reg_dt) VALUES(1,'기한 " +i+"','MID','2026-10-23','2026-10-01')");
        long high=create(1,"OPEN");sql("UPDATE prj.issue SET svrt_cd='HIGH' WHERE issue_id="+high);
        var first=service.list(Map.of());assertThat(first.items()).hasSize(50);assertThat(first.total()).isEqualTo(56);
        assertThat(first.items().get(0).issue_id()).isEqualTo(high);assertThat(service.list(Map.of("page","1")).items()).hasSize(6);
        assertThat(service.list(Map.of("status","OVERDUE")).items()).extracting(IssueListRow::issue_id).containsExactly(high);
    }
}
