package com.sicost.people;

import com.sicost.apiops.*;
import com.sicost.common.api.*;
import com.sicost.common.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PeopleCostPersistenceTest {
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    MockMvc mvc;
    PeopleCostMapper mapper;
    @BeforeEach void isolatedDatabase() throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl!=null&&!adminUrl.isBlank(),"Real PostgreSQL requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER"); password=System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user!=null&&password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()) { com.sicost.support.TestDatabaseDdl.create(s,database); }
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory=new SqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/people/PeopleCostMapper.xml"),new ClassPathResource("mappers/apiops/ProjectBoardMapper.xml"));
        factory.afterPropertiesSet(); var session=new SqlSessionTemplate(factory.getObject());
        mapper=session.getMapper(PeopleCostMapper.class);
        var service=new PeopleCostService(mapper,new ProjectCostService(session.getMapper(ProjectBoardMapper.class)),
            Clock.fixed(Instant.parse("2030-01-15T00:00:00Z"),ZoneId.of("Asia/Seoul")),new PermissionService());
        mvc=MockMvcBuilders.standaloneSetup(new PeopleCostController(service)).setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter()).build();
        sql("INSERT INTO mst.dev(dev_id,mng_no,dev_nm,emp_se_cd,hire_dt,main_job_cd) VALUES(90001,'K-90001','개인 분석 시험','FT','2030-01-01','BE')");
        sql("INSERT INTO prj.prj(prj_id,prj_no,prj_nm,cntrct_ty_cd,prj_stts_cd) VALUES(90001,'P-90001','운영 시험','MO','RUN'),(90002,'P-90002','구축 시험','TK','RUN')");
        sql("INSERT INTO asgn.asgn(asgn_id,asgn_no,prj_id,dev_id,job_cd,bgn_dt,end_dt) VALUES(90001,'A-90001',90001,90001,'BE','2030-01-01','2030-12-31'),(90002,'A-90002',90002,90001,'BE','2030-01-01','2030-12-31')");
        sql("INSERT INTO asgn.asgn_mth(asgn_id,work_ym,mm_qty) VALUES(90001,'2030-01',1),(90002,'2030-01',0.5)");
        sql("INSERT INTO cst.mth_cst(dev_id,cst_ym,emp_se_cd,slry_amt) VALUES(90001,'2030-01','FT',100)");
        sql("INSERT INTO asgn.untprc_hist(asgn_id,aply_bgn_dt,bill_untprc_amt) VALUES(90001,'2030-01-01',200)");
    }
    @AfterEach void removeDatabase() throws Exception {
        if(source==null)return;
        try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()) { com.sicost.support.TestDatabaseDdl.drop(s,database); }
    }
    void sql(String text) throws Exception { try(var c=source.getConnection();var s=c.createStatement()){s.execute(text);} }
    String snapshot() throws Exception {
        try(var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT json_build_object('salary',(SELECT json_agg(s) FROM cst.mth_cst s),'allocation',(SELECT json_agg(a) FROM cst.cst_aloc a),'history',(SELECT count(*) FROM sys.data_chg_hist))::text")) { r.next();return r.getString(1); }
    }
    @Test void realMapperUsesGlobalDenominatorAndDoesNotWriteSources() throws Exception {
        String before=snapshot();
        mvc.perform(get("/api/costs/people").param("year","2030").param("contractType","MO"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].cost").value(67))
            .andExpect(jsonPath("$.data.items[0].profit").value(133)).andExpect(jsonPath("$.data.total").value(1));
        assertThat(snapshot()).isEqualTo(before);
        var population=mapper.population(new PeopleCostModels.Period("2030-01","2030-01"));
        assertThat(population.stream().filter(p->p.dev_id()==90001).findFirst().orElseThrow().salary()).isEqualTo(100);
    }
    @Test void closedSnapshotMissingIsErrorThenFrozenClassificationIsReadWithoutRecalculation() throws Exception {
        sql("INSERT INTO fin.mth_cls(cls_ym,cls_stts_cd) VALUES('2030-01','CLOSED')");
        mvc.perform(get("/api/costs/people/90001/breakdown").param("year","2030")).andExpect(status().isConflict());
        sql("INSERT INTO cst.cst_aloc(cst_ym,dev_id,aloc_ty_cd,lbr_cst_amt,mm_qty) VALUES('2030-01',90001,'IDLE',123,0)");
        String before=snapshot();
        mvc.perform(get("/api/costs/people/90001/breakdown").param("year","2030"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].classification").value("IDLE"))
            .andExpect(jsonPath("$.data.items[0].labor").value(123));
        assertThat(snapshot()).isEqualTo(before);
    }
}
