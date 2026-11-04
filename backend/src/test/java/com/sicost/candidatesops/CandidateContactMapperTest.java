package com.sicost.candidatesops;

import static org.assertj.core.api.Assertions.*;
import static com.sicost.candidatesops.CandidateContactModels.*;
import java.time.*;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class CandidateContactMapperTest {
    Configuration configuration() throws Exception {
        var c=new Configuration();String path="mappers/candidatesops/CandidateContactMapper.xml";
        try(var in=new ClassPathResource(path).getInputStream()){new XMLMapperBuilder(in,c,path,c.getSqlFragments()).parse();}return c;
    }
    String sql(Configuration c,String method,Object parameter){return c.getMappedStatement(CandidateContactMapper.class.getName()+"."+method).getBoundSql(parameter).getSql().replaceAll("\\s+"," ").trim();}
    @Test void everyMapperMethodAndConstructorIsRegistered()throws Exception {
        var c=configuration();for(var method:CandidateContactMapper.class.getDeclaredMethods())assertThat(c.hasStatement(CandidateContactMapper.class.getName()+"."+method.getName())).as(method.getName()).isTrue();
        assertThat(c.getResultMap(CandidateContactMapper.class.getName()+".candidate").getConstructorResultMappings()).hasSize(CandidateRow.class.getRecordComponents().length);
        assertThat(c.getResultMap(CandidateContactMapper.class.getName()+".contact").getConstructorResultMappings()).hasSize(Contact.class.getRecordComponents().length);
        assertThat(c.getResultMap(CandidateContactMapper.class.getName()+".slot").getConstructorResultMappings()).hasSize(SlotRow.class.getRecordComponents().length);
    }
    @Test void contactWriteIsAppendOnlyAndLatestActualDateIsDeterministic()throws Exception {
        var c=configuration();var w=new ContactWrite(1,new ContactInput(LocalDate.of(2026,10,20),"INTV","내용"),null);
        assertThat(sql(c,"lock",1)).endsWith("FOR UPDATE");
        assertThat(sql(c,"append",w)).contains("INSERT INTO hr.cand_cntct_hist","memo_desc,rcrd_user_id","clock_timestamp()").doesNotContain("UPDATE","DELETE");
        assertThat(sql(c,"refreshContact",w)).contains("ORDER BY cntct_dt DESC,created_at DESC,cntct_hist_id DESC","c.dev_id IS NOT NULL THEN 'SIGNED'","IS DISTINCT FROM");
        assertThat(sql(c,"contacts",1)).contains("memo_desc AS memo,rcrd_user_id AS user_id","ORDER BY cntct_dt DESC,created_at DESC,cntct_hist_id DESC");
    }
    @Test void countingUsesDistinctCandidatesRealAvailabilityAndConditionalGrade()throws Exception {
        var c=configuration();var q=new CandidateCount("BE",null,List.of(LocalDate.of(2026,11,30),LocalDate.of(2026,12,31)));
        var statement=c.getMappedStatement(CandidateContactMapper.class.getName()+".candidateCount");
        assertThat(sql(c,"candidateCount",q)).contains("count(DISTINCT c.cand_id)","c.use_yn='Y'","c.cntct_stts_cd IN ('NONE','DONE','INTV')","c.asgn_psbl_dt <= ? OR c.asgn_psbl_dt <= ?").doesNotContain("? IS NULL","c.grd_cd=");
        assertThat(statement.getBoundSql(q).getParameterMappings()).noneMatch(m->m.getProperty().equals("grade"));
        assertThat(sql(c,"candidateCount",new CandidateCount("BE","SR",q.monthEnds()))).contains("c.grd_cd=?");
    }
    @Test void conversionIsConditionalAndCannotWritePayrollCostsOrAssignments()throws Exception {
        String s=sql(configuration(),"convert",new ConversionWrite(7,42,null));
        assertThat(s).contains("dev_id=?","cntct_stts_cd='SIGNED'","WHERE cand_id=? AND dev_id IS NULL").doesNotContain("cst.","asgn.","DELETE");
        assertThat(sql(configuration(),"linkDeveloper",new ConversionWrite(7,42,null)))
            .contains("UPDATE mst.dev SET src_cand_id=?","WHERE dev_id=? AND src_cand_id IS NULL")
            .doesNotContain("cst.","asgn.","DELETE");
    }
}
