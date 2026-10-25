package com.sicost.developers;

import static org.assertj.core.api.Assertions.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import com.sicost.common.api.DeveloperPaging;
import java.time.LocalDate;
import java.util.*;
import static com.sicost.developers.DeveloperModels.*;

class DeveloperMapperTest {
    Configuration configuration()throws Exception {
        var c=new Configuration();String path="mappers/developers/DeveloperMapper.xml";
        try(var in=new ClassPathResource(path).getInputStream()){new XMLMapperBuilder(in,c,path,c.getSqlFragments()).parse();}return c;
    }
    DeveloperQuery query(String use,String status,String emp,String job,String q) {return DeveloperQuery.of(new DeveloperPaging(0,50,null),new DeveloperQuery.Filters(emp,job,status,q,use),LocalDate.of(2026,10,23));}
    String sql(Configuration c,String name,Object p){return c.getMappedStatement(DeveloperMapper.class.getName()+"."+name).getBoundSql(p).getSql();}
    @Test void everyMethodHasAnXmlStatementAndExplicitConstructorMap()throws Exception {
        var c=configuration();for(var m:DeveloperMapper.class.getDeclaredMethods())assertThat(c.hasStatement(DeveloperMapper.class.getName()+"."+m.getName())).as(m.getName()).isTrue();
        assertThat(c.getResultMap(DeveloperMapper.class.getName()+".listRow").getConstructorResultMappings()).hasSize(19);
        assertThat(c.getResultMap(DeveloperMapper.class.getName()+".developerRow").getConstructorResultMappings()).hasSize(16);
    }
    @Test void countAndListHaveIdenticalDynamicScope()throws Exception {
        var c=configuration();var q=query("Y",null,"OS","BE","K-0003");String list=sql(c,"list",q),count=sql(c,"count",q);
        String filter=list.substring(list.indexOf("rows.use_yn="),list.lastIndexOf("ORDER BY dev_nm"));
        assertThat(count).contains(filter.trim());assertThat(list).contains("rows.status!='퇴사'","rows.emp_se_cd=","s.job_cd=","lower(rows.mng_no)").doesNotContain("? IS NULL OR", "FOR UPDATE");
        var mappings=c.getMappedStatement(DeveloperMapper.class.getName()+".list").getBoundSql(q).getParameterMappings();
        assertThat(mappings).noneMatch(p->p.getProperty().equals("status"));
    }
    @Test void disabledScopeIncludesRetirementAndExplicitStatusIsExact()throws Exception {
        var c=configuration();assertThat(sql(c,"list",query("N",null,null,null,null))).doesNotContain("rows.status!=", "rows.status=");
        assertThat(sql(c,"list",query("Y","퇴사",null,null,null))).contains("rows.status=").doesNotContain("rows.status!='퇴사'");
    }
    @Test void statusPriorityInclusiveLeaveAndIndependentMmAggregates()throws Exception {
        String sql=sql(configuration(),"list",query("Y",null,null,null,null));
        assertThat(sql.indexOf("THEN '퇴사'")).isLessThan(sql.indexOf("THEN '휴직'"));assertThat(sql.indexOf("THEN '휴직'")).isLessThan(sql.indexOf("THEN '철수예정'"));
        assertThat(sql).contains("h.end_dt IS NULL OR h.end_dt >=", "h.bgn_dt <=", "a.bgn_dt <=", "a.end_dt >=", "ORDER BY a.bgn_dt,a.asgn_id", "max(a.end_dt)", "m.work_ym=", "a.asgn_stts_cd!='CANCELLED'");
    }
    @Test void gradeUpdateContainsNoIdentityCostOrUnchangedScalars()throws Exception {
        var save=new DeveloperSave(null,"이름","FT","BE",null,null,"DIRECT","SR",List.of(),List.of(),null,null,null,null,null,null);
        String sql=sql(configuration(),"update",new DeveloperWrite(1,"K-0001",save,Set.of("grd_cd"),null));
        assertThat(sql).contains("UPDATE mst.dev", "grd_cd=", "updated_by=").doesNotContain("mng_no=", "emp_se_cd=", "cst.mth_cst", "FOR UPDATE");
    }
}
