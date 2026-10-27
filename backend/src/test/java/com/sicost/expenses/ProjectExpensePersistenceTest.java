package com.sicost.expenses;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiException;
import com.sicost.common.security.*;
import java.sql.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.mybatis.spring.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.sicost.expenses.ProjectExpenseModels.*;

class ProjectExpensePersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    ProjectExpenseService service;
    ProjectExpenseMapper mapper;
    org.springframework.test.web.servlet.MockMvc mvc;
    ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    @BeforeEach void restoreIsolatedDatabase()throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl!=null&&!adminUrl.isBlank(),"Real PostgreSQL integration requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER");password=System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user!=null&&password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()) {
            com.sicost.support.TestDatabaseDdl.create(statement, database);
        }
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/expenses/ProjectExpenseMapper.xml"));factory.afterPropertiesSet();
        mapper=new SqlSessionTemplate(factory.getObject()).getMapper(ProjectExpenseMapper.class);
        context=new AnnotationConfigApplicationContext();
        context.register(Transactions.class,ProjectExpenseService.class,CurrentUser.class,PermissionService.class);
        context.registerBean(ProjectExpenseMapper.class,()->mapper);
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(ObjectMapper.class,()->json);context.refresh();service=context.getBean(ProjectExpenseService.class);
        mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new ProjectExpenseController(service,json))
            .setControllerAdvice(new com.sicost.common.api.ApiExceptionHandler())
            .addFilters(new com.sicost.common.api.RequestIdFilter()).build();
        sql("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cntrct_ty_cd) VALUES(1,'P-2601','내부','IN'),(2,'P-2602','다른 프로젝트','TK')");
        sql("INSERT INTO mst.dev(dev_id,mng_no,dev_nm,emp_se_cd) VALUES(1,'K-0001','개발자','FT')");
        sql("INSERT INTO cst.drct_expns(expns_id,prj_id,dev_id,expns_ym,expns_item_cd,expns_amt) VALUES(101,1,1,'2026-10','TEAM',50000),(102,2,NULL,'2026-10','TRIP',20000),(103,1,NULL,'2026-09','TRIP',10000)");
    }
    @AfterEach void removeIsolatedDatabase()throws Exception {
        if(context!=null)context.close();if(source==null)return;
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()) {
            com.sicost.support.TestDatabaseDdl.drop(statement, database);
        }
    }
    void sql(String sql)throws Exception {try(var c=source.getConnection();var s=c.createStatement()){s.execute(sql);}}
    String value(String sql, Object... args) {return new org.springframework.jdbc.core.JdbcTemplate(source).queryForObject(sql, String.class, args);}
    long historyCount()throws Exception {return Long.parseLong(value("SELECT count(*) FROM sys.data_chg_hist"));}
    String snapshot()throws Exception {
        return value("SELECT json_build_object('expenses',(SELECT json_agg(e ORDER BY expns_id) FROM cst.drct_expns e),'audit',(SELECT json_agg(h ORDER BY data_chg_hist_id) FROM sys.data_chg_hist h))::text");
    }
    String protectedRows()throws Exception {
        return value("SELECT json_agg(e ORDER BY expns_id)::text FROM cst.drct_expns e WHERE dev_id IS NOT NULL OR prj_id=2");
    }
    ExpenseInput input(String month,long amount)throws Exception {
        return ProjectExpenseInput.parse(json.readTree("{\"expns_ym\":\""+month+"\",\"expns_item_cd\":\"TRIP\",\"expns_amt\":"+amount+"}"));
    }
    @Test void internalProjectCrudMaterializesAndKeepsPeopleAndOtherProjects()throws Exception {
        String protectedBefore=protectedRows();long history=historyCount();
        mvc.perform(post("/api/projects/1/expenses").contentType("application/json")
            .content(json.writeValueAsString(input("2026-10",300000)))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.expns_amt").value(300000)).andExpect(jsonPath("$.data.vat_amt").value(30000));
        var created=service.list(1).get(0);
        assertThat(created.vat_amt()).isEqualTo(30000);
        assertThat(value("SELECT dev_id IS NULL FROM cst.drct_expns WHERE expns_id=?", created.expns_id())).isEqualTo("t");
        assertThat(service.list(1)).extracting(ProjectExpense::expns_id).containsExactly(created.expns_id(),103L);
        mvc.perform(put("/api/projects/1/expenses/{expnsId}",created.expns_id()).contentType("application/json")
            .content(json.writeValueAsString(new ExpenseInput("2026-11","TRIP",400009,123,
                java.time.LocalDate.of(2026,11,2),"수정")))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.expns_amt").value(400009));
        var changed=service.list(1).get(0);
        assertThat(value("SELECT prj_id FROM cst.drct_expns WHERE expns_id=?",created.expns_id())).isEqualTo("1");
        assertThat(value("SELECT dev_id FROM cst.drct_expns WHERE expns_id=?",created.expns_id())).isNull();
        assertThat(changed.expns_id()).isEqualTo(created.expns_id());assertThat(changed.pay_dt()).hasToString("2026-11-02");
        assertThat(service.list(1).get(0)).isEqualTo(changed);
        mvc.perform(delete("/api/projects/1/expenses/{expnsId}",created.expns_id())).andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));
        assertThat(service.list(1)).extracting(ProjectExpense::expns_id).containsExactly(103L);
        assertThat(historyCount()).isEqualTo(history+3);
        assertThat(value("SELECT string_agg(data_chg_ty_cd,',' ORDER BY data_chg_hist_id) FROM sys.data_chg_hist WHERE tbl_nm='cst.drct_expns' AND obj_rec_id='1' AND chg_user_id IS NULL"))
            .isEqualTo("INSERT,UPDATE,DELETE");
        assertThat(protectedRows()).isEqualTo(protectedBefore);
        assertThat(value("SELECT bfr_val::jsonb->>'rmrk_desc' FROM sys.data_chg_hist WHERE data_chg_ty_cd='DELETE'"))
            .isEqualTo("수정");
    }
    @ParameterizedTest @ValueSource(strings={"POST","PUT","DELETE"})
    void closedMonthNeverWritesOrAudits(String method)throws Exception {
        String before=snapshot();
        assertThatThrownBy(()->{switch(method){
            case "POST" -> service.create(1,input("2026-09",300000));
            case "PUT" -> service.update(1,103,input("2026-10",300000));
            default -> service.delete(1,103);
        }}).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("CLOSED_MONTH"));
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void targetClosedMonthRejectsMove()throws Exception {
        var row=service.create(1,input("2026-10",300000));String before=snapshot();
        assertThatThrownBy(()->service.update(1,row.expns_id(),input("2026-09",300000))).isInstanceOf(ApiException.class);
        assertThat(snapshot()).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(longs={101,102,999})
    void foreignAndPersonRowsAreNotFoundForBothMutations(long id)throws Exception {
        String before=snapshot();
        assertThatThrownBy(()->service.update(1,id,input("2026-10",300000)))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status()).isEqualTo(404));
        assertThatThrownBy(()->service.delete(1,id))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status()).isEqualTo(404));
        assertThat(snapshot()).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"POST","PUT","DELETE"})
    void summaryInsertFailureRollsBackExpenseMutation(String method)throws Exception {
        var row=service.create(1,input("2026-10",300000));String before=snapshot();
        sql("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_history CHECK(false) NOT VALID");
        assertThatThrownBy(()->{switch(method){
            case "POST" -> service.create(1,input("2026-10",300000));
            case "PUT" -> service.update(1,row.expns_id(),input("2026-11",400000));
            default -> service.delete(1,row.expns_id());
        }}).isInstanceOf(RuntimeException.class);
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void activeSelectionAndMissingMonthDefaultsToOpen()throws Exception {
        sql("UPDATE cmn.cd_dtl SET use_yn='N' WHERE cd_grp_id='EXPENSE_ITEM' AND dtl_cd='TRIP'");
        String before=snapshot();assertThatThrownBy(()->service.create(1,input("2028-01",300000))).isInstanceOf(ApiException.class);
        assertThat(snapshot()).isEqualTo(before);
        sql("UPDATE cmn.cd_dtl SET use_yn='Y' WHERE cd_grp_id='EXPENSE_ITEM' AND dtl_cd='TRIP'");
        var row=service.create(1,input("2028-01",300000));assertThat(service.list(1)).contains(row);
    }
    @Test void disabledProjectReadableButNoMutation()throws Exception {
        sql("UPDATE prj.prj SET use_yn='N' WHERE prj_id=1");String before=snapshot();
        assertThat(service.list(1)).hasSize(1);
        assertThatThrownBy(()->service.create(1,input("2026-10",300000)))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.field()).isEqualTo("use_yn"));
        assertThat(snapshot()).isEqualTo(before);
    }
}
