package com.sicost.partneractivation;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.security.*;
import com.sicost.partners.*;
import com.sicost.partners.PartnerModels.PartnerSave;
import com.sicost.partnersops.*;
import com.sicost.partnersops.PartnerLifecycleModels.PartnerOption;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;

class PartnerEnablePersistenceTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    AnnotationConfigApplicationContext context;
    PartnerService partners;
    PartnerLifecycleService lifecycle;

    @BeforeEach void restoreIsolatedDatabase()throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl!=null&&!adminUrl.isBlank(),"Real PostgreSQL integration requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER");password=System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user!=null&&password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()) {
            statement.execute("CREATE DATABASE "+database+" TEMPLATE template0");
        }
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);factory.setMapperLocations(
            new ClassPathResource("mappers/partners/PartnerMapper.xml"),new ClassPathResource("mappers/partnersops/PartnerLifecycleMapper.xml"));
        factory.afterPropertiesSet();var session=new SqlSessionTemplate(factory.getObject());
        context=new AnnotationConfigApplicationContext();
        context.register(Transactions.class,PartnerService.class,PartnerLifecycleService.class,CurrentUser.class,PermissionService.class,ExcelExportService.class);
        context.registerBean(PartnerMapper.class,()->session.getMapper(PartnerMapper.class));
        context.registerBean(PartnerLifecycleMapper.class,()->session.getMapper(PartnerLifecycleMapper.class));
        context.registerBean(ObjectMapper.class,()->new ObjectMapper());
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(Clock.class,()->Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"),ZoneId.of("Asia/Seoul")));
        context.refresh();partners=context.getBean(PartnerService.class);lifecycle=context.getBean(PartnerLifecycleService.class);
    }
    @AfterEach void removeIsolatedDatabase()throws Exception {
        if(context!=null)context.close();if(source==null)return;
        try(Connection admin=DriverManager.getConnection(adminUrl,user,password);var statement=admin.createStatement()) {
            statement.execute("DROP DATABASE "+database+" WITH (FORCE)");
        }
    }
    String createStopped(String kind) {
        var row=partners.create(new PartnerSave(kind,"고객사 A","123-45-67890",null,null,null,null,"비고",
            kind.equals("VEND")?null:"tax@company.co.kr",kind.equals("CUST")?null:"004",
            kind.equals("CUST")?null:"001-23456789",kind.equals("CUST")?null:"협력사 B"));
        lifecycle.disable(List.of(row.ptnr_id()));return row.ptnr_id();
    }
    void sql(String sql)throws Exception {try(var c=source.getConnection();var s=c.createStatement()){s.execute(sql);}}
    String value(String sql)throws Exception {
        try(var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery(sql)) {
            assertThat(r.next()).isTrue();return r.getString(1);
        }
    }
    String snapshot()throws Exception {
        return value("SELECT json_build_object('partners',(SELECT json_agg(p ORDER BY ptnr_id) FROM mst.ptnr p),"
            +"'history',(SELECT json_agg(h ORDER BY data_chg_hist_id) FROM sys.data_chg_hist h))::text");
    }
    long enableHistory(String id)throws Exception {
        return Long.parseLong(value("SELECT count(*) FROM sys.data_chg_hist WHERE tbl_nm='mst.ptnr' AND obj_rec_id='"+id
            +"' AND data_chg_ty_cd='UPDATE' AND clmn_nm='use_yn' AND bfr_val='N' AND aft_val='Y' AND chg_user_id IS NULL"));
    }
    @ParameterizedTest @ValueSource(strings={"CUST","VEND","BOTH"})
    void reappearsInActiveListAndApplicableOptionsWithOriginalIdentityAndOneAudit(String kind)throws Exception {
        String id=createStopped(kind);
        var before=partners.get(Long.parseLong(id));
        String business=value("SELECT (to_jsonb(p)-'use_yn'-'updated_at'-'updated_by')::text FROM mst.ptnr p WHERE ptnr_id="+id);
        assertThat(partners.list(new PageRequest(1,50,null),null,"Y",null).items()).isEmpty();
        for(String optionKind:List.of("CUST","VEND"))assertThat(lifecycle.options(optionKind)).isEmpty();
        var enabled=lifecycle.enable(id);
        assertThat(enabled.use_yn()).isEqualTo("Y");
        assertThat(enabled.ptnr_id()).isEqualTo(before.ptnr_id());
        assertThat(enabled.ptnr_no()).isEqualTo(before.ptnr_no());
        assertThat(enabled.biz_reg_no()).isEqualTo(before.biz_reg_no());
        assertThat(value("SELECT use_yn FROM mst.ptnr WHERE ptnr_id="+id)).isEqualTo("Y");
        assertThat(value("SELECT (to_jsonb(p)-'use_yn'-'updated_at'-'updated_by')::text FROM mst.ptnr p WHERE ptnr_id="+id)).isEqualTo(business);
        assertThat(partners.list(new PageRequest(1,50,null),null,"Y",null).items()).extracting(com.sicost.partners.PartnerModels.PartnerListItem::ptnr_id).containsExactly(id);
        for(String optionKind:List.of("CUST","VEND")) {
            var options=lifecycle.options(optionKind);
            if(kind.equals(optionKind)||kind.equals("BOTH"))assertThat(options).extracting(PartnerOption::id).containsExactly(id);
            else assertThat(options).isEmpty();
        }
        assertThat(enableHistory(id)).isEqualTo(1);
        assertThat(lifecycle.history(id)).anySatisfy(h->{
            assertThat(h.changeType()).isEqualTo("UPDATE");assertThat(h.itemName()).isEqualTo("사용 여부");
            assertThat(h.before()).isEqualTo("중지");assertThat(h.after()).isEqualTo("사용");
            assertThat(h.actorName()).isEqualTo("기본 사용자");
        });
    }
    @Test void repeatedEnableDoesNotUpdateTimestampOrAppendHistory()throws Exception {
        String id=createStopped("CUST");lifecycle.enable(id);String before=snapshot();
        lifecycle.enable(id);assertThat(snapshot()).isEqualTo(before);assertThat(enableHistory(id)).isEqualTo(1);
    }
    @Test void enableDoesNotRejectOrRewriteExistingConnections()throws Exception {
        String id=createStopped("BOTH");
        sql("INSERT INTO prj.prj(prj_no,prj_nm,cust_ptnr_id,cntrct_ty_cd,prj_stts_cd) VALUES('P-201','가 프로젝트',"+id+",'TK','RUN')");
        sql("INSERT INTO mst.dev(mng_no,dev_nm,vndr_ptnr_id,emp_se_cd) VALUES('K-301','가 개발자',"+id+",'OS')");
        String projects=value("SELECT json_agg(p ORDER BY prj_id)::text FROM prj.prj p");
        String developers=value("SELECT json_agg(d ORDER BY dev_id)::text FROM mst.dev d");
        lifecycle.enable(id);
        assertThat(value("SELECT json_agg(p ORDER BY prj_id)::text FROM prj.prj p")).isEqualTo(projects);
        assertThat(value("SELECT json_agg(d ORDER BY dev_id)::text FROM mst.dev d")).isEqualTo(developers);
        assertThat(enableHistory(id)).isEqualTo(1);
    }
    @Test void auditFailureRollsBackStateAndTimestamp()throws Exception {
        String id=createStopped("CUST");String before=snapshot();
        sql("ALTER TABLE sys.data_chg_hist ADD CONSTRAINT reject_partner_enable CHECK (NOT (tbl_nm='mst.ptnr' AND data_chg_ty_cd='UPDATE' AND clmn_nm='use_yn')) NOT VALID");
        assertThatThrownBy(()->lifecycle.enable(id)).isInstanceOf(RuntimeException.class);
        assertThat(snapshot()).isEqualTo(before);assertThat(enableHistory(id)).isZero();
    }
    @Test void missingEnableCannotCreateAnyPartnerOrAudit()throws Exception {
        String before=snapshot();
        assertThatThrownBy(()->lifecycle.enable("9223372036854775807")).isInstanceOf(ApiException.class);
        assertThat(snapshot()).isEqualTo(before);
    }
}
