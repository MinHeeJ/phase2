package com.sicost.candidates;

import static org.assertj.core.api.Assertions.*;
import java.time.LocalDate;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class CandidateMapperTest {
    Configuration configuration() throws Exception {
        var c=new Configuration();String path="mappers/candidates/CandidateMapper.xml";
        try(var in=new ClassPathResource(path).getInputStream()) {new XMLMapperBuilder(in,c,path,c.getSqlFragments()).parse();}return c;
    }
    String sql(Configuration c,String name,Object p) {return c.getMappedStatement(CandidateMapper.class.getName()+"."+name).getBoundSql(p).getSql().replaceAll("\\s+"," ").trim();}
    CandidateQuery query(Map<String,String> q) {return CandidateQuery.of(q,LocalDate.of(2026,10,23));}
    @Test void everyMethodAndConstructorIsMapped() throws Exception {
        var c=configuration();for(var m:CandidateMapper.class.getDeclaredMethods())assertThat(c.hasStatement(CandidateMapper.class.getName()+"."+m.getName())).as(m.getName()).isTrue();
        var records=Map.of("candidateRow",CandidateModels.CandidateRow.class,"candidateKpi",CandidateModels.CandidateKpi.class,"candidateContact",CandidateModels.CandidateContact.class,"candidateSlot",CandidateModels.CandidateSlotSource.class);
        records.forEach((k,v) -> assertThat(c.getResultMap(CandidateMapper.class.getName()+"."+k).getConstructorResultMappings()).hasSize(v.getRecordComponents().length));
    }
    @Test void listAndKpiShareAllAndPredicatesAndStrictBoundaryBeforePagination() throws Exception {
        var c=configuration();var q=query(Map.of("q","이름","job","BE","grade","SENIOR","skill","JAVA","shortage_month","2026-11","uncontacted_only","true"));
        String list=sql(c,"list",q),kpi=sql(c,"kpi",q);String predicates=list.substring(list.indexOf("WHERE r.use_yn"),list.lastIndexOf("ORDER BY"));
        assertThat(kpi).endsWith(predicates.trim());assertThat(list).contains("r.use_yn='Y'","r.job_cd=","r.grd_cd=","r.avail_dt <=","coalesce(r.last_cntct_dt,r.created_at::date) < ?","r.cntct_stts_cd IN ('NONE','DONE','INTV')","LIMIT ? OFFSET ?").doesNotContain("? IS NULL","FOR UPDATE");
    }
    @Test void absentFiltersNeverBindNullValues() throws Exception {
        var c=configuration();var bound=c.getMappedStatement(CandidateMapper.class.getName()+".list").getBoundSql(query(Map.of()));
        assertThat(bound.getParameterMappings()).extracting(org.apache.ibatis.mapping.ParameterMapping::getProperty).containsExactly("size","offset");
    }
    @Test void projectedStatusUsesDeterministicLatestActualContactAndConvertedSigned() throws Exception {
        String row=sql(configuration(),"row",1L);assertThat(row).contains("c.asgn_psbl_dt AS avail_dt","c.cand_src_cd AS source","c.memo_desc AS rmrk_desc","WHEN c.dev_id IS NOT NULL THEN 'SIGNED'","x.cntct_dt DESC,x.created_at DESC,x.cntct_hist_id DESC","c.use_yn='Y'");
    }
    @Test void writesUseExactPhysicalColumnsGeneratedKeyAndRowLock() throws Exception {
        var c=configuration();var input=new CandidateModels.CandidateWrite("후보","FL",null,"BE",null,List.of(),0L,LocalDate.of(2026,11,1),"NONE",null,null,null);
        assertThat(sql(c,"insert",new CandidateModels.CandidateInsert(1,"CAND-0001",input,null))).contains("asgn_psbl_dt","cand_src_cd","memo_desc","RETURNING cand_id");
        assertThat(sql(c,"lock",1L)).endsWith("FOR UPDATE");
        assertThat(sql(c,"update",new CandidateModels.CandidateUpdate(1,Map.of("cand_nm","수정"),null))).contains("cand_nm=").doesNotContain("dev_id=","cand_no=","last_cntct_dt=");
    }
    @Test void matchingReadsOnlyOpenActiveSameJobBeforeStartMonthEnd() throws Exception {
        assertThat(sql(configuration(),"slots",1L)).contains("n.job_cd=c.job_cd","n.use_yn='Y'","o.oppt_stts_cd='OPEN'","c.asgn_psbl_dt <=","interval '1 month - 1 day'","s.oprt_std_id='TEAM_FEE'");
    }
}
