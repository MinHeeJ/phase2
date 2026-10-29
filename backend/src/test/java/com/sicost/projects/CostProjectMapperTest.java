package com.sicost.projects;

import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.*;
import static com.sicost.projects.CostProjectModels.*;

class CostProjectMapperTest {
    Configuration configuration() throws Exception {
        var c=new Configuration();c.setMapUnderscoreToCamelCase(true);c.setArgNameBasedConstructorAutoMapping(true);
        for(String path:List.of("mappers/projects/CostProjectMapper.xml","mappers/billingsops/BillingMapper.xml",
            "mappers/apiops/ProjectBoardMapper.xml","mappers/projectboardops/ProjectBoardExportMapper.xml","mappers/projectsops/ProjectProgressMapper.xml"))
            try(var input=new ClassPathResource(path).getInputStream()) {new XMLMapperBuilder(input,c,path,c.getSqlFragments()).parse();}
        return c;
    }
    @Test void allProjectionLabelsTypesAndConstructorOrderAreExplicitWithoutDatabase() throws Exception {
        var c=configuration();
        var projections=Map.of("com.sicost.projects.CostProjectMapper.source",Source.class,
            "com.sicost.projects.CostProjectMapper.person",PersonName.class,
            "com.sicost.billingsops.BillingMapper.strictProjectRevenue",com.sicost.billingsops.BillingRevenueQuery.ProjectRevenue.class,
            "com.sicost.apiops.ProjectBoardMapper.source",com.sicost.apiops.ProjectBoardModels.BoardSource.class,
            "com.sicost.projectboardops.ProjectBoardExportMapper.source",com.sicost.projectboardops.ProjectBoardExportModels.BoardExportSource.class);
        for(var entry:projections.entrySet()) {
            var components=entry.getValue().getRecordComponents();var mappings=c.getResultMap(entry.getKey()).getConstructorResultMappings();
            assertThat(mappings).hasSize(components.length);
            for(int i=0;i<components.length;i++) {assertThat(mappings.get(i).getColumn()).isEqualTo(components[i].getName());assertThat(mappings.get(i).getJavaType()).isEqualTo(components[i].getType());}
        }
        for(var method:CostProjectMapper.class.getDeclaredMethods())assertThat(c.hasStatement(CostProjectMapper.class.getName()+"."+method.getName())).isTrue();
    }
    @Test void optionalFiltersOnlyBindPresentValuesAndNeverNeedNullTypeInference() throws Exception {
        var c=configuration();var clock=java.time.Clock.fixed(java.time.Instant.parse("2026-10-29T00:00:00Z"),java.time.ZoneOffset.UTC);
        var base=CostProjectInput.query(Map.of("year","2026"),clock);
        var filtered=CostProjectInput.query(Map.of("year","2026","type","MO","status","RUN","partId","7","q","검색"),clock);
        var statement=c.getMappedStatement(CostProjectMapper.class.getName()+".sources");
        var sql=statement.getBoundSql(filtered).getSql();
        assertThat(sql).contains("p.cntrct_ty_cd=?","p.bsn_part_id=?","p.prj_stts_cd=?","strpos").doesNotContain("? IS NULL");
        assertThat(statement.getBoundSql(base).getParameterMappings()).noneMatch(m->Set.of("type","status","partId","q").contains(m.getProperty()));
    }
    @Test void strictRevenueNeverUsesStageDateReceiptsVatOrPersonalRevenue() throws Exception {
        var c=configuration();
        String sql=c.getMappedStatement("com.sicost.billingsops.BillingMapper.strictProjectRevenue").getBoundSql(new com.sicost.billingsops.BillingModels.Scope(17L,"2026",null)).getSql();
        assertThat(sql).contains("b.bill_stts_cd='CONFIRM'","b.bill_dt IS NULL","b.atrb_ym","sum(b.sply_amt)")
            .doesNotContain("bill_plan_dt","rcpt","vat_amt","tk_aloc");
    }
}
