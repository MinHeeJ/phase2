package com.sicost.developersops;

import static org.assertj.core.api.Assertions.*;
import static com.sicost.developersops.DeveloperLifecycleModels.*;
import java.time.LocalDate;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class DeveloperLifecycleMapperTest {
    Configuration configuration()throws Exception{
        var c=new Configuration();String path="mappers/developersops/DeveloperLifecycleMapper.xml";
        try(var in=new ClassPathResource(path).getInputStream()){new XMLMapperBuilder(in,c,path,c.getSqlFragments()).parse();}return c;
    }
    @Test void everyInterfaceMethodHasAnXmlStatement()throws Exception{var c=configuration();for(var method:DeveloperLifecycleMapper.class.getDeclaredMethods())assertThat(c.hasStatement(DeveloperLifecycleMapper.class.getName()+"."+method.getName())).as(method.getName()).isTrue();}
    @Test void exportIsUnpagedAndSeparatesAssignmentAndMonthAggregates()throws Exception{
        var s=configuration().getMappedStatement(DeveloperLifecycleMapper.class.getName()+".exportRows");
        var q=new DeveloperExportQuery("FT","BE","대기","이름","Y",LocalDate.of(2026,10,23));var sql=s.getBoundSql(q);
        assertThat(sql.getSql()).contains("LEFT JOIN LATERAL","sum(m.mm_qty)","array_agg(p.prj_nm ORDER BY a.bgn_dt,a.asgn_id)","a.asgn_stts_cd!='CANCELLED'","r.status=","r.emp_se_cd=","mst.dev_sub_job").doesNotContain("LIMIT","OFFSET","IS NULL OR column");
        assertThat(sql.getParameterMappings()).extracting(p->p.getProperty()).contains("today","empType","job","status","q","use_yn");
    }
    @Test void countAndPageUseIdenticalFiltersAndDeletedAuditOwner()throws Exception{
        var c=configuration();var q=new DeveloperReadQuery(3,"use_yn","2025-11","2026-10","\"changed_at\" desc",20,20);
        var list=c.getMappedStatement(DeveloperLifecycleMapper.class.getName()+".history").getBoundSql(q).getSql();var count=c.getMappedStatement(DeveloperLifecycleMapper.class.getName()+".historyCount").getBoundSql(q).getSql();
        assertThat(list.substring(list.indexOf("FROM sys.data_chg_hist"),list.indexOf("ORDER BY")).trim()).isEqualTo(count.substring(count.indexOf("FROM sys.data_chg_hist")).trim());
        assertThat(list).contains("h.bfr_val::jsonb->>'dev_id'","h.aft_val::jsonb->>'dev_id'","pg_input_is_valid","h.clmn_nm=");
    }
    @Test void absentOptionSearchDoesNotBindNull()throws Exception{
        var q=new DeveloperOptionQuery(null,LocalDate.of(2026,10,23));var sql=configuration().getMappedStatement(DeveloperLifecycleMapper.class.getName()+".options").getBoundSql(q);
        assertThat(sql.getSql()).doesNotContain("LIMIT","OFFSET","position(");assertThat(sql.getParameterMappings()).allMatch(p->p.getProperty().equals("today"));
    }
    @Test void defaultExportUsesTheSameRetirementScopeAsTheList()throws Exception{
        var statement=configuration().getMappedStatement(DeveloperLifecycleMapper.class.getName()+".exportRows");
        var today=LocalDate.of(2026,10,23);
        var active=statement.getBoundSql(new DeveloperExportQuery(null,null,null,null,"Y",today)).getSql();
        var disabled=statement.getBoundSql(new DeveloperExportQuery(null,null,null,null,"N",today)).getSql();
        var retired=statement.getBoundSql(new DeveloperExportQuery(null,null,"퇴사",null,"Y",today)).getSql();
        assertThat(active).contains("r.status!='퇴사'");
        assertThat(disabled).doesNotContain("r.status!='퇴사'");
        assertThat(retired).contains("r.status=").doesNotContain("r.status!='퇴사'");
    }
}
