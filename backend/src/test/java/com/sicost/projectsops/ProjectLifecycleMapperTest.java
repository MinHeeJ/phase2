package com.sicost.projectsops;

import static org.assertj.core.api.Assertions.*;
import static com.sicost.projectsops.ProjectLifecycleModels.*;
import java.time.LocalDate;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class ProjectLifecycleMapperTest {
    Configuration configuration()throws Exception{
        var c=new Configuration();String path="mappers/projectsops/ProjectLifecycleMapper.xml";
        try(var in=new ClassPathResource(path).getInputStream()){new XMLMapperBuilder(in,c,path,c.getSqlFragments()).parse();}return c;
    }
    @Test void everyInterfaceMethodResolvesToPackagedXml()throws Exception{
        var c=configuration();for(var method:ProjectLifecycleMapper.class.getDeclaredMethods())assertThat(c.hasStatement(ProjectLifecycleMapper.class.getName()+"."+method.getName())).as(method.getName()).isTrue();
    }
    @Test void nullableFiltersAreOmittedFromParametersNotBoundAsNull()throws Exception{
        var date=LocalDate.of(2026,10,23);
        var q=new ProjectExportQuery(2026,date.withDayOfYear(1),LocalDate.of(2026,12,31),null,null,null,false,null,"\"cntrct_bgn_dt\" desc NULLS LAST, prj_id ASC",date);
        var sql=configuration().getMappedStatement(ProjectLifecycleMapper.class.getName()+".exportRows").getBoundSql(q);
        assertThat(sql.getParameterMappings()).extracting(p->p.getProperty()).containsOnly("today","yearEnd","yearStart");
        assertThat(sql.getSql()).doesNotContain("LIMIT","OFFSET").contains("NOT IN ('END','LOST')","count(DISTINCT a.dev_id)");
    }
    @Test void populatedFiltersHaveOnlyBeanBindablePropertiesAndSafeSort()throws Exception{
        var date=LocalDate.of(2026,10,23);
        var q=new ProjectExportQuery(2026,date.withDayOfYear(1),LocalDate.of(2026,12,31),"TK","END",3L,false,"고객","\"prj_nm\" asc NULLS LAST, prj_id ASC",date);
        var c=configuration();var sql=c.getMappedStatement(ProjectLifecycleMapper.class.getName()+".exportRows").getBoundSql(q);
        var values=c.newMetaObject(q);for(var p:sql.getParameterMappings())assertThat(values.getValue(p.getProperty())).isNotNull();
        assertThat(sql.getSql()).contains("ORDER BY \"prj_nm\" asc NULLS LAST, prj_id ASC");
    }
    @Test void assignmentConstructorHasExactlyContractProjection()throws Exception{
        var c=configuration();var map=c.getResultMap(ProjectLifecycleMapper.class.getName()+".assignmentRow");
        assertThat(map.getConstructorResultMappings()).extracting(p->p.getColumn()).containsExactly("name","job","beginDate","endDate","assignmentRate","status");
    }
    @Test void optionsWithoutQDoNotBindNull()throws Exception{
        var sql=configuration().getMappedStatement(ProjectLifecycleMapper.class.getName()+".options").getBoundSql(Map.of());
        assertThat(sql.getParameterMappings()).isEmpty();assertThat(sql.getSql()).doesNotContain("position(");
    }
}
