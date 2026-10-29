package com.sicost.projects;

import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.*;
import static com.sicost.projects.ProjectProfitModels.*;

class ProjectProfitMapperTest {
    Configuration configuration() throws Exception {
        var c=new Configuration();
        c.setMapUnderscoreToCamelCase(true);
        c.setArgNameBasedConstructorAutoMapping(true);
        for (String path:List.of("mappers/projects/ProjectProfitMapper.xml","mappers/apiops/ProjectBoardMapper.xml",
                "mappers/projectsops/ProjectProgressMapper.xml","mappers/projectboardops/ProjectBoardExportMapper.xml")) {
            try(var input=new ClassPathResource(path).getInputStream()) {
                new XMLMapperBuilder(input,c,path,c.getSqlFragments()).parse();
            }
        }
        return c;
    }
    @Test void everyRecordProjectionAndSharedBudgetConstructorMatchesExactlyWithoutDatabase() throws Exception {
        var c=configuration();
        var projections=Map.of(
            "com.sicost.projects.ProjectProfitMapper.source",Source.class,
            "com.sicost.projects.ProjectProfitMapper.plan",Plan.class,
            "com.sicost.projects.ProjectProfitMapper.forecast",Forecast.class,
            "com.sicost.projects.ProjectProfitMapper.person",Person.class,
            "com.sicost.apiops.ProjectBoardMapper.source",com.sicost.apiops.ProjectBoardModels.BoardSource.class,
            "com.sicost.projectboardops.ProjectBoardExportMapper.source",com.sicost.projectboardops.ProjectBoardExportModels.BoardExportSource.class);
        for(var entry:projections.entrySet()) {
            var components=entry.getValue().getRecordComponents();
            var mappings=c.getResultMap(entry.getKey()).getConstructorResultMappings();
            assertThat(mappings).hasSize(components.length);
            for(int i=0;i<components.length;i++) {
                assertThat(mappings.get(i).getColumn()).isEqualTo(components[i].getName());
                assertThat(mappings.get(i).getJavaType()).isEqualTo(components[i].getType());
            }
        }
        for(var method:ProjectProfitMapper.class.getDeclaredMethods()) assertThat(c.hasStatement(ProjectProfitMapper.class.getName()+"."+method.getName())).isTrue();
    }
    @Test void filtersOnlyBindPresentPredicatesAndUseSameUnpagedSetForTotal() throws Exception {
        var statement=configuration().getMappedStatement(ProjectProfitMapper.class.getName()+".list");
        var absent=statement.getBoundSql(new Filter(2026,null,null,null,null));
        assertThat(absent.getParameterMappings()).hasSize(2);
        var present=statement.getBoundSql(new Filter(2026,"TK",1L,"RUN","사업"));
        assertThat(present.getSql()).contains("p.cntrct_ty_cd=?","p.bsn_part_id=?","p.prj_stts_cd=?","p.prj_nm ILIKE");
        assertThat(present.getSql()).doesNotContain("? IS NULL","OFFSET");
        assertThat(present.getSql()).endsWith("ORDER BY p.prj_id");
    }
    @Test void forecastChoosesOneStagePerProjectMonthAndRequiresPersonPriceEvidence() throws Exception {
        var statement=configuration().getMappedStatement(ProjectProfitMapper.class.getName()+".forecast");
        var sql=statement.getBoundSql(new Period(7,"2026-01","2026-10")).getSql();
        assertThat(sql).contains("PARTITION BY m.aloc_ym", "WHEN 'ACTUAL' THEN 1 WHEN 'FINAL' THEN 2", "s.indv_lbr_cst_amt AS monthly_cost", "r.line_ty_cd='PERSON'");
        assertThat(sql).doesNotContain("need_psn_cnt","UPDATE","INSERT");
    }
}
