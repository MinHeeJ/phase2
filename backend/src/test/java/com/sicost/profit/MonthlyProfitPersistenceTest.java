package com.sicost.profit;

import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.mybatis.spring.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static com.sicost.profit.MonthlyProfitModels.*;

class MonthlyProfitPersistenceTest {
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    MonthlyProfitMapper mapper;
    @BeforeEach void restoreIsolatedDatabase() throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl!=null&&!adminUrl.isBlank(),"Real PostgreSQL integration requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER");password=System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user!=null&&password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(var admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()) {
            com.sicost.support.TestDatabaseDdl.create(statement,database);
        }
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/profit/MonthlyProfitMapper.xml"));factory.afterPropertiesSet();
        mapper=new SqlSessionTemplate(factory.getObject()).getMapper(MonthlyProfitMapper.class);
    }
    @AfterEach void removeIsolatedDatabase() throws Exception {
        if(source==null)return;
        try(var admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()) {
            com.sicost.support.TestDatabaseDdl.drop(statement,database);
        }
    }
    void sql(String sql) throws Exception {try(var connection=source.getConnection();var statement=connection.createStatement()){statement.execute(sql);}}
    @Test void snapshotsHaveExactPhysicalColumnsAndSourceBoundary() throws Exception {
        sql("INSERT INTO fin.mth_pl(pl_ym,pl_src_cd,sls_amt,aloc_cst_amt,idle_cst_amt,drct_expns_amt,sga_input_amt,indrct_lbr_cst_amt,rnd_cst_amt,prvmn_adj_amt,nop_incm_amt,nop_exp_amt,corp_tax_amt) VALUES('2041-01','CLOSE',1000,100,20,12,50,33,44,-7,8,9,10)");
        var row=mapper.snapshot(new SnapshotKey("2041-01","CLOSE"));
        assertThat(((Number)row.get("operating_profit")).longValue()).isEqualTo(734);
        assertThat(((Number)row.get("net_profit")).longValue()).isEqualTo(723);
        assertThat(mapper.snapshot(new SnapshotKey("2041-01","IMPORT"))).isNull();
    }
    @Test void deletedEntriesAreExcludedButSavedAutomaticEntriesAreIncluded() throws Exception {
        sql("INSERT INTO fin.entry(entry_ym,fin_se_cd,item_nm,entry_amt,auto_fill_yn,deleted_yn) VALUES('2041-02','SGA','자동저장',10,'Y','N'),('2041-02','SGA','삭제',999,'N','Y'),('2041-03','NOI','다른월',888,'N','N')");
        assertThat(mapper.entries("2041-02")).containsExactly(new Entry("SGA","자동저장",10));
        assertThat(mapper.entries("2041-04")).isEmpty();
    }
    @Test void partAttributionUsesMonthHistoryNotCurrentRepresentative() throws Exception {
        sql("INSERT INTO mst.bsn_part(bsn_part_id,bsn_part_nm) VALUES(91001,'이전파트'),(91002,'현재파트')");
        sql("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cntrct_ty_cd,bsn_part_id) VALUES(91001,'P-4191','기간프로젝트','TK',91002)");
        sql("INSERT INTO prj.prj_part_hist(prj_id,aply_bgn_ym,bsn_part_id) VALUES(91001,'2041-01',91001),(91001,'2041-06',91002)");
        assertThat(mapper.project(new ProjectMonth("2041-03",91001)).part_id()).isEqualTo(91001L);
        assertThat(mapper.project(new ProjectMonth("2041-07",91001)).part_id()).isEqualTo(91002L);
    }
}
