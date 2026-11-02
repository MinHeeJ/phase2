package com.sicost.entries;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiException;
import com.sicost.common.security.*;
import java.sql.*;
import java.time.*;
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
import static com.sicost.entries.FinanceEntryModels.*;

class FinanceEntryPersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    FinanceEntryService service;
    FinanceEntryMapper mapper;
    ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    @BeforeEach void restoreIsolatedDatabase()throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl!=null&&!adminUrl.isBlank(),"Real PostgreSQL integration requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER");password=System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user!=null&&password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()) {com.sicost.support.TestDatabaseDdl.create(statement,database);}
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/entries/FinanceEntryMapper.xml"));factory.afterPropertiesSet();
        mapper=new SqlSessionTemplate(factory.getObject()).getMapper(FinanceEntryMapper.class);
        context=new AnnotationConfigApplicationContext();context.register(Transactions.class,FinanceEntryService.class,CurrentUser.class,PermissionService.class);
        context.registerBean(FinanceEntryMapper.class,()->mapper);
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(ObjectMapper.class,()->json);
        context.registerBean(Clock.class,()->Clock.fixed(Instant.parse("2026-10-30T00:00:00Z"),ZoneId.of("Asia/Seoul")));
        context.refresh();service=context.getBean(FinanceEntryService.class);
    }
    @AfterEach void removeIsolatedDatabase()throws Exception {
        if(context!=null)context.close();if(source==null)return;
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()){com.sicost.support.TestDatabaseDdl.drop(statement,database);}
    }
    void sql(String sql)throws Exception {try(var c=source.getConnection();var s=c.createStatement()){s.execute(sql);}}
    String value(String sql,Object...args){return new org.springframework.jdbc.core.JdbcTemplate(source).queryForObject(sql,String.class,args);}
    EntryInput input(String month){return new EntryInput(month,"SGA","고유 경비",300000,123,null,"Y","N","비고",null,"N");}
    String snapshot(){return value("SELECT json_build_object('entries',(SELECT json_agg(e ORDER BY entry_id) FROM fin.entry e),'audit',(SELECT json_agg(h ORDER BY data_chg_hist_id) FROM sys.data_chg_hist h WHERE tbl_nm='fin.entry'))::text");}
    @Test void crudMapsRemarkAndGeneratedIdentityAndFullMonthCount()throws Exception {
        var created=service.create(input("2026-10"));assertThat(created.entry_id()).isPositive();
        assertThat(value("SELECT rmrk_desc FROM fin.entry WHERE entry_id=?",created.entry_id())).isEqualTo("비고");
        assertThat(service.list("2026-10").items()).contains(created);
        assertThat(service.list("2026-10").total()).isEqualTo(service.list("2026-10").items().size());
        assertThat(service.list("2026-10").months()).hasSize(12);
        service.update(created.entry_id(),new EntryInput("2026-10","NOI","이자",0,0,LocalDate.of(2026,10,29),"N","Y","수정",null,"N"));
        assertThat(mapper.row(created.entry_id()).entry_amt()).isZero();
        assertThat(value("SELECT aft_val::jsonb->>'remark' FROM sys.data_chg_hist WHERE tbl_nm='fin.entry' AND obj_rec_id=? AND data_chg_ty_cd='UPDATE'",created.entry_id().toString())).isEqualTo("수정");
        service.delete(created.entry_id());assertThat(mapper.row(created.entry_id())).isNull();
        assertThat(value("SELECT deleted_yn FROM fin.entry WHERE entry_id=?",created.entry_id())).isEqualTo("Y");
    }
    @Test void previewDoesNotWriteAndSoftDeletedCopyStillPreventsRepeat()throws Exception {
        var original=service.create(input("2026-10"));
        sql("UPDATE fin.entry SET entry_ym='2026-09',inout_dt='2026-09-30',inout_cmpl_yn='Y' WHERE entry_id="+original.entry_id());
        String before=snapshot();var preview=service.preview("2026-10",true,java.util.List.of(original.entry_id())).items().get(0);
        assertThat(snapshot()).isEqualTo(before);assertThat(preview.entry_id()).isNull();assertThat(preview.inout_cmpl_yn()).isEqualTo("N");
        var saved=service.create(new EntryInput(preview.entry_ym(),preview.fin_se_cd(),preview.item_nm(),preview.entry_amt(),preview.vat_amt(),preview.inout_dt(),preview.rpt_yn(),preview.inout_cmpl_yn(),preview.remark(),preview.copy_entry_id(),preview.auto_fill_yn()));
        service.delete(saved.entry_id());
        assertThat(service.preview("2026-10",true,null).items().get(0).already_copied()).isTrue();
        assertThatThrownBy(()->service.create(new EntryInput("2026-10","SGA","복제",1,0,null,"N","N",null,original.entry_id(),"Y")))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("DUPLICATE_SOURCE"));
        assertThat(value("SELECT copy_entry_id FROM fin.entry WHERE entry_id=?",saved.entry_id())).isEqualTo(original.entry_id().toString());
    }
    @Test void deletingSourceKeepsChildReference()throws Exception {
        var parent=service.create(input("2026-10"));var child=service.create(input("2026-10"));
        sql("UPDATE fin.entry SET copy_entry_id="+parent.entry_id()+" WHERE entry_id="+child.entry_id());service.delete(parent.entry_id());
        assertThat(mapper.row(child.entry_id()).copy_entry_id()).isEqualTo(parent.entry_id());
    }
    @ParameterizedTest @ValueSource(strings={"POST","PUT","DELETE"}) void auditFailureRollsBackEverything(String method)throws Exception {
        var row=service.create(input("2026-10"));String before=snapshot();
        sql("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_history CHECK(false) NOT VALID");
        assertThatThrownBy(()->{switch(method){case "POST"->service.create(input("2026-10"));case "PUT"->service.update(row.entry_id(),input("2026-10"));default->service.delete(row.entry_id());}}).isInstanceOf(RuntimeException.class);
        assertThat(snapshot()).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"POST","PUT","DELETE"}) void closedPreservesOriginalAndAudit(String method)throws Exception {
        var row=service.create(input("2026-10"));sql("UPDATE fin.entry SET entry_ym='2026-09' WHERE entry_id="+row.entry_id());String before=snapshot();
        assertThatThrownBy(()->{switch(method){case "POST"->service.create(input("2026-09"));case "PUT"->service.update(row.entry_id(),input("2026-10"));default->service.delete(row.entry_id());}})
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("CLOSED_MONTH"));assertThat(snapshot()).isEqualTo(before);
    }
}
