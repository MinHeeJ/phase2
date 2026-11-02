package com.sicost.businessplan;

import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.*;
import static com.sicost.businessplan.BusinessPlanModels.*;

class BusinessPlanMapperTest {
    Configuration configuration()throws Exception {
        var c=new Configuration();c.setMapUnderscoreToCamelCase(true);c.setArgNameBasedConstructorAutoMapping(true);
        String path="mappers/businessplan/BusinessPlanMapper.xml";
        try(var input=new ClassPathResource(path).getInputStream()){new XMLMapperBuilder(input,c,path,c.getSqlFragments()).parse();}
        return c;
    }
    @Test void allXmlStatementsAndConstructorTypesResolve()throws Exception {
        var c=configuration();String ns=BusinessPlanMapper.class.getName()+".";
        for(var method:BusinessPlanMapper.class.getDeclaredMethods())assertThat(c.hasStatement(ns+method.getName())).isTrue();
        for(var entry:Map.of("plan",Plan.class,"part",Part.class,"attribution",Attribution.class,"actual",ClosedActual.class).entrySet()) {
            var components=entry.getValue().getRecordComponents();var mappings=c.getResultMap(ns+entry.getKey()).getConstructorResultMappings();
            assertThat(mappings).hasSize(components.length);
            for(int i=0;i<components.length;i++)assertThat(mappings.get(i).getJavaType()).isEqualTo(components[i].getType());
        }
    }
    @Test void upsertUsesGeneratedKeyAndKeepsHeaderYearWhileBindingOnlyRecordProperties()throws Exception {
        var c=configuration();var statement=c.getMappedStatement(BusinessPlanMapper.class.getName()+".upsert");
        var input=new Plan(2026,10,7L,100,20,java.math.BigDecimal.TEN);
        var bound=statement.getBoundSql(new PlanWrite(input,"2026-10",null));
        assertThat(bound.getSql()).contains("RETURNING biz_plan_id","ON CONFLICT ON CONSTRAINT uk_biz_plan_plan_ym_bsn_part_id");
        var meta=c.newMetaObject(new PlanWrite(input,"2026-10",null));
        assertThat(bound.getParameterMappings()).allSatisfy(p->assertThat(meta.hasGetter(p.getProperty())).isTrue());
    }
}
