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
        var c=new Configuration();
        c.setMapUnderscoreToCamelCase(true);
        c.setArgNameBasedConstructorAutoMapping(true);
        for (String path:List.of("mappers/projects/CostProjectMapper.xml","mappers/apiops/ProjectBoardMapper.xml",
                "mappers/projectsops/ProjectProgressMapper.xml","mappers/projectboardops/ProjectBoardExportMapper.xml")) {
            try (var input=new ClassPathResource(path).getInputStream()) {
                new XMLMapperBuilder(input,c,path,c.getSqlFragments()).parse();
            }
        }
        return c;
    }
    @Test void projectionsMatchEveryConstructorIncludingStoredBoardBudget() throws Exception {
        var c=configuration();
        var projections=Map.of(
            "com.sicost.projects.CostProjectMapper.source",Source.class,
            "com.sicost.projects.CostProjectMapper.plan",Plan.class,
            "com.sicost.projects.CostProjectMapper.labor",Labor.class,
            "com.sicost.projects.CostProjectMapper.reference",Reference.class,
            "com.sicost.apiops.ProjectBoardMapper.source",com.sicost.apiops.ProjectBoardModels.BoardSource.class,
            "com.sicost.projectboardops.ProjectBoardExportMapper.source",com.sicost.projectboardops.ProjectBoardExportModels.BoardExportSource.class);
        for (var entry:projections.entrySet()) {
            var components=entry.getValue().getRecordComponents();
            var mappings=c.getResultMap(entry.getKey()).getConstructorResultMappings();
            assertThat(mappings).hasSize(components.length);
            for (int i=0;i<components.length;i++) {
                assertThat(mappings.get(i).getColumn()).isEqualTo(components[i].getName());
                assertThat(mappings.get(i).getJavaType()).isEqualTo(components[i].getType());
            }
        }
        for (var method:CostProjectMapper.class.getDeclaredMethods())
            assertThat(c.hasStatement(CostProjectMapper.class.getName()+"."+method.getName())).isTrue();
    }
    @Test void absentFiltersHaveNoBindAndPresentFiltersUseOneSharedReadScope() throws Exception {
        var c=configuration();
        var statement=c.getMappedStatement(CostProjectMapper.class.getName()+".sources");
        var paging=com.sicost.common.api.BillingPaging.of(null,null,null);
        var absent=statement.getBoundSql(new Query(2026,null,null,null,null,paging));
        assertThat(absent.getParameterMappings()).extracting(m->m.getProperty()).containsOnly("year");
        var present=statement.getBoundSql(new Query(2026,"MO",1L,"RUN","이름",paging));
        assertThat(present.getParameterMappings()).extracting(m->m.getProperty()).contains("type","partId","status","q");
        assertThat(present.getSql()).doesNotContain("IS NULL OR").contains("ORDER BY p.prj_id");
    }
}
