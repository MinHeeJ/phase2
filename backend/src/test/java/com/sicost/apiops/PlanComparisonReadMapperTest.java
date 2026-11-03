package com.sicost.apiops;

import com.sicost.us4.PlanComparisonMapper;
import com.sicost.us4.PlanComparisonModels.Query;
import java.util.List;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.*;

class PlanComparisonReadMapperTest {
    private Configuration configuration() throws Exception {
        var configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setArgNameBasedConstructorAutoMapping(true);
        String path = "mappers/us4/PlanComparisonMapper.xml";
        try (var input = new ClassPathResource(path).getInputStream()) {
            new XMLMapperBuilder(input, configuration, path, configuration.getSqlFragments()).parse();
        }
        return configuration;
    }
    @Test void allStatementsRemainReadOnlyAndTemporaryAssignmentsStaySeparate() throws Exception {
        var configuration = configuration();
        String namespace = PlanComparisonMapper.class.getName() + ".";
        var query = new Query(2026, null, null, null, "gapMm,asc", 0, 50);
        for (var method : PlanComparisonMapper.class.getDeclaredMethods()) {
            var statement = configuration.getMappedStatement(namespace + method.getName());
            assertThat(statement.getSqlCommandType()).isEqualTo(SqlCommandType.SELECT);
            Object parameter = method.getName().equals("tolerance") ? "2026-10" : query;
            assertThat(statement.getBoundSql(parameter).getSql().toUpperCase())
                .doesNotContain("INSERT ", "UPDATE ", "DELETE ", "TMPR_ASGN", "BSN.OPPT_NEED");
        }
    }
    @Test void optionalPredicatesBindOnlyPresentValues() throws Exception {
        var statement = configuration().getMappedStatement(PlanComparisonMapper.class.getName() + ".annual");
        var unfiltered = statement.getBoundSql(new Query(2026, null, null, null, "gapMm,asc", 0, 50));
        assertThat(unfiltered.getParameterMappings()).extracting(p -> p.getProperty())
            .containsExactly("end", "start", "fromMonth", "toMonth", "fromMonth", "toMonth");
        var filtered = statement.getBoundSql(new Query(2026, 42L, "BE", null, "gapMm,asc", 0, 50));
        assertThat(filtered.getParameterMappings()).extracting(p -> p.getProperty())
            .containsExactly("end", "start", "projectId", "fromMonth", "toMonth", "job", "fromMonth", "toMonth", "job");
        assertThat(filtered.getSql()).contains("p.prj_id=?", "s.job_cd=?", "a.job_cd=?");
    }
    @Test void constructorProjectionKeepsDecimalSumsAndSourceRowsWithoutGradeFanout() throws Exception {
        var configuration = configuration();
        String namespace = PlanComparisonMapper.class.getName() + ".";
        var mappings = configuration.getResultMap(namespace + "annualRow").getConstructorResultMappings();
        assertThat(mappings).extracting(m -> m.getColumn()).containsExactly(
            "prj_id", "prj_no", "prj_nm", "job_cd", "job_nm", "job_order", "plan_mm", "actual_mm");
        for (var mapping : mappings.subList(6, 8)) {
            assertThat(mapping.getJavaType().getName()).isEqualTo(java.math.BigDecimal.class.getName());
        }
        var sql = configuration.getMappedStatement(namespace + "annual")
            .getBoundSql(new Query(2026, null, null, null, "gapMm,asc", 0, 50)).getSql();
        assertThat(sql).contains("SUM(s.need_mm_qty)", "SUM(m.mm_qty)", "FULL OUTER JOIN", "a.asgn_stts_cd!='CANCELLED'");
        assertThat(sql).doesNotContain("src_need_id IS NULL", "grd_cd=", "SUM(DISTINCT");
    }
}
