package com.sicost.opportunitiesops;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import static com.sicost.opportunitiesops.OpportunityLifecycleModels.*;

class OpportunityLifecycleMapperTest {
    Configuration configuration()throws Exception{
        var c=new Configuration();for(String name:List.of("OpportunityLifecycleMapper","OpportunityForecastMapper")){
            String path="mappers/opportunitiesops/"+name+".xml";
            try(var in=new ClassPathResource(path).getInputStream()){new XMLMapperBuilder(in,c,path,c.getSqlFragments()).parse();}
        }return c;
    }
    @Test void bothInterfacesResolveEveryStatement()throws Exception{
        var c=configuration();for(var type:List.of(OpportunityLifecycleMapper.class,OpportunityForecastMapper.class))for(var method:type.getDeclaredMethods())
            assertThat(c.hasStatement(type.getName()+"."+method.getName())).as(method.getName()).isTrue();
    }
    @Test void scopedLockUnionsActionQualifiedGrantsAndFailsClosedWhenEmpty()throws Exception{
        var c=configuration();var statement=c.getMappedStatement(OpportunityLifecycleMapper.class.getName()+".lockScoped");
        var scope=new com.sicost.common.security.ScopePredicate(13L,null,List.of(
            new com.sicost.common.security.ScopePredicate.Grant(4L,com.sicost.common.security.DataScope.PART,Set.of(1L),Set.of(),false,false),
            new com.sicost.common.security.ScopePredicate.Grant(5L,com.sicost.common.security.DataScope.PROJECT,Set.of(),Set.of(99L),false,false),
            new com.sicost.common.security.ScopePredicate.Grant(6L,com.sicost.common.security.DataScope.SELF,Set.of(),Set.of(),false,false)));
        var sql=statement.getBoundSql(Map.of("id",7L,"scope",scope));
        assertThat(sql.getSql()).contains("AND (FALSE","OR bsn_part_id IN","OR win_prj_id IN","OR bsn_chrg_user_id=","FOR UPDATE");
        assertThat(sql.getParameterMappings()).hasSize(4);
        var empty=statement.getBoundSql(Map.of("id",7L,"scope",new com.sicost.common.security.ScopePredicate(13L,null,List.of())));
        assertThat(empty.getSql()).contains("AND (FALSE").doesNotContain("OR TRUE"," IN ");
    }
    @Test void exportOmitsAbsentOptionalBindsAndNeverCutsToPage()throws Exception{
        var c=configuration();var q=OpportunityLifecycleInput.export(Map.of());var sql=c.getMappedStatement(OpportunityLifecycleMapper.class.getName()+".exportRows").getBoundSql(q);
        assertThat(sql.getParameterMappings()).extracting(p->p.getProperty()).containsExactly("status");assertThat(sql.getSql()).doesNotContain("LIMIT","OFFSET","IS NULL OR").contains("ORDER BY o.oppt_id");
    }
    @Test void exportAllFiltersAreAndAndBeanBindable()throws Exception{
        var c=configuration();var q=OpportunityLifecycleInput.export(Map.of("q","고객","oppt_stts_cd","WON","bsn_stg_cd","PROP","cust_ptnr_id","3","bsn_part_id","1","from","2026-11","to","2027-02"));
        var sql=c.getMappedStatement(OpportunityLifecycleMapper.class.getName()+".exportRows").getBoundSql(q);var bean=c.newMetaObject(q);
        for(var parameter:sql.getParameterMappings())assertThat(bean.getValue(parameter.getProperty())).isNotNull();assertThat(sql.getSql()).contains("AND o.cust_ptnr_id=","AND o.bsn_part_id=","o.expc_mnth_qty-1","AND o.expc_bgn_ym<=");
    }
    @Test void approvalCostSourcesUsePersonalLatestMonthAndSameSeoulDateAsSaving()throws Exception{
        var sql=configuration().getMappedStatement(OpportunityLifecycleMapper.class.getName()+".costSources").getBoundSql(LocalDate.of(2026,10,23));
        assertThat(sql.getSql()).contains("JOIN LATERAL","m.dev_id=d.dev_id","ORDER BY m.cst_ym DESC LIMIT 1","d.use_yn='Y'","d.rsgn_dt","mst.dev_work_stts_hist");
        assertThat(sql.getParameterMappings()).extracting(p->p.getProperty()).containsOnly("today");
    }
    @Test void forecastCandidateCountMatchesShortageEligibilityAndMonthEnd()throws Exception{
        var filter=new OpportunityForecastModels.CandidateFilter("BE","SR","2027-02");
        var sql=configuration().getMappedStatement(OpportunityForecastMapper.class.getName()+".candidates").getBoundSql(filter);
        assertThat(sql.getSql()).contains("count(DISTINCT cand_id)","use_yn='Y'","('NONE','DONE','INTV')","interval '1 month - 1 day'").doesNotContain("'HOLD'","'SIGNED'");
    }
    @Test void changesOnlyBindServerProducedColumnsAndNullableActor()throws Exception{
        var c=configuration();var change=new Change(7,Map.of("bsn_stg_cd","PROP","win_prblt_rt",30),null);
        var sql=c.getMappedStatement(OpportunityLifecycleMapper.class.getName()+".change").getBoundSql(change);
        assertThat(sql.getSql()).contains("bsn_stg_cd=","win_prblt_rt=","oppt_stts_cd='OPEN'");assertThat(sql.getParameterMappings()).extracting(p->p.getProperty()).contains("actor","id");
    }
    @Test void forecastFiltersDoNotCreateNullPredicates()throws Exception{
        var c=configuration();var q=OpportunityForecastInput.parse(Map.of("from","2026-11"),Clock.fixed(Instant.parse("2026-10-23T00:00:00Z"),ZoneId.of("Asia/Seoul")));
        var sql=c.getMappedStatement(OpportunityForecastMapper.class.getName()+".opportunities").getBoundSql(q);
        assertThat(sql.getParameterMappings()).isEmpty();assertThat(sql.getSql()).contains("oppt_stts_cd='OPEN'").doesNotContain("IS NULL OR");
    }
    @Test void lockOrderAndLifecycleSqlPreserveHistoryRows()throws Exception{
        var c=configuration();String base=OpportunityLifecycleMapper.class.getName()+".";
        assertThat(c.getMappedStatement(base+"lock").getBoundSql(7).getSql()).contains("FOR UPDATE");
        assertThat(c.getMappedStatement(base+"lockNeeds").getBoundSql(7).getSql()).contains("ORDER BY need_id FOR UPDATE");
        assertThat(c.getMappedStatement(base+"release").getBoundSql(new Change(7,Map.of(),null)).getSql()).contains("'RELEASED'","tmpr_asgn_stts_cd='ACTIVE'").doesNotContain("DELETE");
        assertThat(c.getMappedStatement(base+"approval").getBoundSql(new ApprovalWrite(7,"APPROVE",null,null,80,null)).getSql()).contains("INSERT INTO bsn.quot_aprv_hist");
    }
    @Test void constructorMapsMatchTheCurrentRecordsExactly()throws Exception{
        var c=configuration();for(String id:c.getResultMapNames()){
            if(!id.startsWith("com.sicost.opportunitiesops."))continue;
            var map=c.getResultMap(id);
            if(map.getType().getPackageName().equals("com.sicost.opportunitiesops")&&map.getType().isRecord()){
                var components=map.getType().getRecordComponents();var args=map.getConstructorResultMappings();assertThat(args).hasSize(components.length);
                for(int i=0;i<components.length;i++)assertThat(args.get(i).getJavaType()).as(map.getId()+"."+components[i].getName()).isEqualTo(components[i].getType());
            }
        }
    }
}
