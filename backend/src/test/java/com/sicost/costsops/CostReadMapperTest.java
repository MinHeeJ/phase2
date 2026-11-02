package com.sicost.costsops;

import com.sicost.costallocation.CostAllocationMapper;
import com.sicost.people.PeopleMapper;
import com.sicost.people.PeopleModels;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import static com.sicost.costallocation.CostAllocationModels.*;
import static org.assertj.core.api.Assertions.*;

class CostReadMapperTest {
    Configuration configuration() throws Exception {
        var configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setArgNameBasedConstructorAutoMapping(true);
        for (String path : List.of("mappers/costallocation/CostAllocationMapper.xml", "mappers/people/PeopleMapper.xml")) {
            try (var input = new ClassPathResource(path).getInputStream()) {
                new XMLMapperBuilder(input, configuration, path, configuration.getSqlFragments()).parse();
            }
        }
        return configuration;
    }
    @Test void bothReadPathsHaveRegisteredStatementsAndExactConstructorMappings() throws Exception {
        var configuration = configuration();
        for (var mapper : List.of(CostAllocationMapper.class, PeopleMapper.class)) {
            for (var method : mapper.getDeclaredMethods()) {
                assertThat(configuration.hasStatement(mapper.getName() + "." + method.getName())).isTrue();
            }
        }
        for (var entry : Map.of(
            "com.sicost.costallocation.CostAllocationMapper.allocation", AllocationRow.class,
            "com.sicost.people.PeopleMapper.personMonth", PeopleModels.PersonMonth.class,
            "com.sicost.people.PeopleMapper.projectLink", PeopleModels.ProjectLink.class).entrySet()) {
            var mapping = configuration.getResultMap(entry.getKey()).getConstructorResultMappings();
            var fields = entry.getValue().getRecordComponents();
            assertThat(mapping).hasSize(fields.length);
            for (int i = 0; i < fields.length; i++) {
                assertThat(mapping.get(i).getColumn()).isEqualTo(fields[i].getName());
                assertThat(mapping.get(i).getJavaType()).isEqualTo(fields[i].getType());
            }
        }
    }
    @Test void missingSnapshotPredicatesBindOnlyPresentProjectAndRecordComponents() throws Exception {
        var statement = configuration().getMappedStatement(CostAllocationMapper.class.getName() + ".expectedCost");
        var unfiltered = statement.getBoundSql(new Query("2026-10", null));
        var filtered = statement.getBoundSql(new Query("2026-10", 71L));
        assertThat(unfiltered.getParameterMappings()).allMatch(p -> p.getProperty().equals("month"));
        assertThat(filtered.getParameterMappings()).anyMatch(p -> p.getProperty().equals("projectId"));
        assertThat(unfiltered.getSql()).doesNotContain("IS NULL OR", "COALESCE(?", "a.prj_id=?");
        assertThat(filtered.getSql()).contains("a.prj_id=?");
    }
    @Test void snapshotReadDoesNotJoinOrReadMutableCostSources() throws Exception {
        var statement = configuration().getMappedStatement(CostAllocationMapper.class.getName() + ".snapshots");
        var sql = statement.getBoundSql("2026-10").getSql();
        assertThat(sql).contains("FROM cst.cst_aloc", "c.cst_ym=?", "c.dev_id", "c.asgn_id", "c.aloc_rt");
        assertThat(sql).doesNotContain("cst.mth_cst", "asgn.asgn", "JOIN", "INSERT", "UPDATE", "DELETE");
        assertThat(statement.getResultMaps().get(0).getConstructorResultMappings().get(1).getJavaType()).isEqualTo(Long.class);
    }
}
