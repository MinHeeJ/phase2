package com.sicost.projectboardops;

import static org.assertj.core.api.Assertions.*;
import com.sicost.projectcost.ProjectCostMapper;
import java.time.LocalDate;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class ProjectBoardExportMapperTest {
    Configuration configuration() throws Exception {
        var c=new Configuration();
        for (String path : new String[]{"mappers/projectboardops/ProjectBoardExportMapper.xml","mappers/projectcost/ProjectCostMapper.xml"})
            try (var in=new ClassPathResource(path).getInputStream()) { new XMLMapperBuilder(in,c,path,c.getSqlFragments()).parse(); }
        return c;
    }
    @Test void bothMapperInterfacesResolveToPackagedXml() throws Exception {
        var c=configuration();
        for (Class<?> mapper : new Class<?>[]{ProjectBoardExportMapper.class,ProjectCostMapper.class})
            for (var method : mapper.getDeclaredMethods()) assertThat(c.hasStatement(mapper.getName()+"."+method.getName())).as(method.getName()).isTrue();
    }
    @Test void emptyFiltersDoNotBindNullOrIntroducePagination() throws Exception {
        var c=configuration(); var query=ProjectBoardExportService.query(Map.of(),LocalDate.of(2026,10,23));
        var sql=c.getMappedStatement(ProjectBoardExportMapper.class.getName()+".sources").getBoundSql(query);
        assertThat(sql.getParameterMappings()).extracting(p->p.getProperty()).containsOnly("today");
        assertThat(sql.getSql()).doesNotContain("OFFSET","p.cntrct_ty_cd=?","p.bsn_part_id=?");
    }
    @Test void populatedFiltersBindOnlyRecordComponents() throws Exception {
        var c=configuration(); var query=ProjectBoardExportService.query(Map.of("type","TK","part","7","light","위험"),LocalDate.of(2026,10,23));
        var sql=c.getMappedStatement(ProjectBoardExportMapper.class.getName()+".sources").getBoundSql(query);
        var values=c.newMetaObject(query);
        for (var mapping : sql.getParameterMappings()) assertThat(values.getValue(mapping.getProperty())).isNotNull();
        assertThat(sql.getParameterMappings()).extracting(p->p.getProperty()).contains("today","type","part").doesNotContain("light");
    }
    @Test void costQueriesBindExactMonthAndProjectComponentsAndUseClosedReplacementSources() throws Exception {
        var c=configuration(); var query=new ProjectCostMapper.CostQuery(1,"2026-10");
        for (String method : new String[]{"monthlyCosts","missingSalaryMonths"}) {
            var sql=c.getMappedStatement(ProjectCostMapper.class.getName()+"."+method).getBoundSql(query);
            var values=c.newMetaObject(query);
            for (var mapping : sql.getParameterMappings()) assertThat(values.getValue(mapping.getProperty())).isNotNull();
            assertThat(sql.getSql()).contains("f.cls_ym=c.cst_ym","f.cls_stts_cd='CLOSED'").doesNotContain("c.work_ym");
        }
    }
    @Test void sourceConstructorMatchesAllRecordComponentsIncludingNullableDates() throws Exception {
        var map=configuration().getResultMap(ProjectBoardExportMapper.class.getName()+".source");
        assertThat(map.getConstructorResultMappings()).hasSize(ProjectBoardExportModels.BoardExportSource.class.getRecordComponents().length);
        assertThat(map.getConstructorResultMappings()).extracting(p->p.getColumn()).contains("custName","currentPhase","memoProgress","last_memo_dt","prfm_bgn_dt");
    }
}
