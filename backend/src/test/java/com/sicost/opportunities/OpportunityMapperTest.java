package com.sicost.opportunities;

import static org.assertj.core.api.Assertions.*;
import static com.sicost.opportunities.OpportunityModels.*;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class OpportunityMapperTest {
    Configuration configuration()throws Exception{var c=new Configuration();String path="mappers/opportunities/OpportunityMapper.xml";try(var in=new ClassPathResource(path).getInputStream()){new XMLMapperBuilder(in,c,path,c.getSqlFragments()).parse();}return c;}
    String sql(Configuration c,String name,Object p){return c.getMappedStatement(OpportunityMapper.class.getName()+"."+name).getBoundSql(p).getSql().replaceAll("\\s+"," ").trim();}
    @Test void methodsRegisteredAndConstructorsMatch()throws Exception{var c=configuration();for(var m:OpportunityMapper.class.getDeclaredMethods())assertThat(c.hasStatement(OpportunityMapper.class.getName()+"."+m.getName())).as(m.getName()).isTrue();for(var entry:Map.of("row",Row.class,"need",NeedRow.class,"approval",Approval.class,"cost",CostSource.class).entrySet())assertThat(c.getResultMap(OpportunityMapper.class.getName()+"."+entry.getKey()).getConstructorResultMappings()).hasSize(entry.getValue().getRecordComponents().length);}
    @Test void listCountAndKpiMatchingShareFilterNotPagination()throws Exception{var c=configuration();var q=OpportunityQuery.of(Map.of("q","검색","oppt_stts_cd","WON","cust_ptnr_id","3","bsn_part_id","2","from","2026-10","to","2027-01","sort","expc_amt,desc"));var list=sql(c,"list",q);var where=list.substring(list.indexOf("WHERE o.oppt_stts_cd"),list.lastIndexOf("ORDER BY"));assertThat(sql(c,"count",q)).endsWith(where.trim());assertThat(sql(c,"matching",q)).contains(where.trim()).doesNotContain("LIMIT","OFFSET");assertThat(list).contains("ORDER BY \"expc_amt\" desc NULLS LAST, oppt_id ASC LIMIT ? OFFSET ?");}
    @Test void absentOptionalValuesNeverBindNullAndPhysicalWireNamesAreMapped()throws Exception{var c=configuration();var b=c.getMappedStatement(OpportunityMapper.class.getName()+".list").getBoundSql(OpportunityQuery.of(Map.of()));assertThat(b.getParameterMappings()).noneMatch(p->Set.of("q","part","customer","from","to","stage").contains(p.getProperty()));assertThat(b.getSql()).contains("bsn_chrg_user_id AS owner_user_id","expc_bgn_ym AS bgn_ym","expc_mnth_qty AS mth_cnt").doesNotContain("? IS NULL");}
    @Test void onlyWriteLockAndRemovalRetainsHistory()throws Exception{var c=configuration();assertThat(sql(c,"lock",1L)).endsWith("FOR UPDATE");assertThat(sql(c,"row",1L)).doesNotContain("FOR UPDATE");assertThat(sql(c,"retireNeed",1L)).contains("use_yn='N'").doesNotContain("DELETE");assertThat(sql(c,"releaseNeed",1L)).contains("tmpr_asgn_stts_cd='RELEASED'","tmpr_asgn_stts_cd='ACTIVE'");assertThat(sql(c,"needs",1L)).contains("use_yn='Y'");}
    @Test void realLatestCostExcludesCurrentLeaveAndUsesGeneratedLaborNotSalaryInterpolation()throws Exception{var q=sql(configuration(),"costs",java.time.LocalDate.of(2026,10,23));assertThat(q).contains("c.indv_lbr_cst_amt","sum(e.expns_amt)","ORDER BY m.cst_ym DESC LIMIT 1","NOT EXISTS","h.work_stts_cd IN");assertThat(q).doesNotContain("slry_amt","INSERT","UPDATE");}
}
