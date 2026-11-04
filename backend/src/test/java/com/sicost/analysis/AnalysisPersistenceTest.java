package com.sicost.analysis;

import java.sql.*;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import static com.sicost.analysis.AnalysisModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AnalysisPersistenceTest {
    String adminUrl,user,password,database;
    DriverManagerDataSource source;
    ConversationMapper mapper;
    AnalysisMapper analysis;
    JdbcTemplate jdbc;
    @BeforeEach void isolatedDatabase()throws Exception {
        adminUrl=System.getenv("TEST_DATABASE_URL");assumeTrue(adminUrl!=null&&!adminUrl.isBlank(),"Real PostgreSQL requires TEST_DATABASE_URL");
        user=System.getenv("TEST_DATABASE_USER");password=System.getenv("TEST_DATABASE_PASSWORD");assumeTrue(user!=null&&password!=null,"Test database connection settings are absent");
        database="sicost_test_"+UUID.randomUUID().toString().replace("-","");
        try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()){com.sicost.support.TestDatabaseDdl.create(s,database);}
        source=new DriverManagerDataSource(adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)+database,user,password);jdbc=new JdbcTemplate(source);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);factory.setMapperLocations(new ClassPathResource("mappers/analysis/ConversationMapper.xml"),new ClassPathResource("mappers/analysis/AnalysisMapper.xml"));factory.afterPropertiesSet();
        var session=new SqlSessionTemplate(factory.getObject());mapper=session.getMapper(ConversationMapper.class);analysis=session.getMapper(AnalysisMapper.class);
    }
    @AfterEach void removeDatabase()throws Exception {if(source==null)return;try(var c=DriverManager.getConnection(adminUrl,user,password);var s=c.createStatement()){com.sicost.support.TestDatabaseDdl.drop(s,database);}}
    @Test void realConversationSqlLatestFeedbackSoftDeleteAndRetention() {
        var at=LocalDateTime.of(2026,11,4,9,0);String owner="a".repeat(64),foreign="b".repeat(64);
        long own=mapper.create(new ConversationWrite(owner,at,at.plusMinutes(30),at.plusDays(30)));
        mapper.create(new ConversationWrite(foreign,at,at.plusMinutes(30),at.plusDays(30)));
        var query=new ConversationQuery(owner,null,at,0,50);assertThat(mapper.count(query)).isEqualTo(1);assertThat(mapper.conversations(query)).extracting(ConversationRow::id).containsExactly(own);
        long message=mapper.append(new MessageWrite(own,null,"안전한 질문","안전한 답",null,null,"[]","MODEL_UNAVAILABLE",at));
        mapper.feedback(new FeedbackWrite(message,"HELPFUL",null));mapper.feedback(new FeedbackWrite(message,"INACCURATE","조건 재확인"));
        assertThat(mapper.message(message).rating()).isEqualTo("INACCURATE");assertThat(mapper.messageCount(own)).isEqualTo(1);
        mapper.hide(new MessageQuery(own,0,50));assertThat(mapper.count(query)).isZero();assertThat(mapper.messageCount(own)).isEqualTo(1);
        mapper.clearContext(at.plusDays(29));mapper.cleanupMessages(at.plusDays(29));mapper.cleanupConversations(at.plusDays(29));assertThat(mapper.message(message)).isNotNull();
        mapper.clearContext(at.plusDays(30));mapper.cleanupMessages(at.plusDays(30));mapper.cleanupConversations(at.plusDays(30));assertThat(mapper.message(message)).isNull();assertThat(mapper.conversation(own)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai.qry_hist",Long.class)).isZero();
    }
    @Test void officialMarkerDoesNotRewriteLegacyAndPartialUniquePreventsDuplicate() {
        var at=LocalDateTime.of(2026,11,4,9,0);var write=new ForecastWrite("2026-10","REVENUE",6,"{}",at);
        long first=analysis.forecast(write);assertThat(analysis.forecast(write)).isNull();
        analysis.forecastValue(new ForecastValue(first,"2026-11","",null,new java.math.BigDecimal("100"),null));
        assertThat(analysis.savedForecasts("2026-11")).extracting(SavedForecast::forecastValue).containsExactly(new java.math.BigDecimal("100.00"));
        assertThat(analysis.wasClosed("2026-10")).isFalse();
    }
}
