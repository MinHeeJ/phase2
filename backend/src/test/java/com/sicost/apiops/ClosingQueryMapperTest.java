package com.sicost.apiops;

import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.*;
import static com.sicost.apiops.ClosingQueryModels.*;

class ClosingQueryMapperTest {
    Configuration configuration() throws Exception {
        var configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setArgNameBasedConstructorAutoMapping(true);
        String path = "mappers/apiops/ClosingQueryMapper.xml";
        try (var input = new ClassPathResource(path).getInputStream()) {
            new XMLMapperBuilder(input,configuration,path,configuration.getSqlFragments()).parse();
        }
        return configuration;
    }
    @Test void allStatementsAreReadOnlyAndEveryConstructorHasExactLabelsAndTypes() throws Exception {
        var configuration = configuration();
        String namespace = ClosingQueryMapper.class.getName() + ".";
        for (var method : ClosingQueryMapper.class.getDeclaredMethods()) {
            var statement = configuration.getMappedStatement(namespace + method.getName());
            assertThat(statement.getSqlCommandType()).isEqualTo(SqlCommandType.SELECT);
            assertThat(statement.getBoundSql(Map.of("month","2026-10")).getSql())
                .doesNotContain("INSERT ","UPDATE ","DELETE ","FOR UPDATE");
        }
        for (var projection : Map.of("state",State.class,"check",StoredCheck.class).entrySet()) {
            var components = projection.getValue().getRecordComponents();
            var mappings = configuration.getResultMap(namespace + projection.getKey()).getConstructorResultMappings();
            assertThat(mappings).hasSize(components.length);
            for (int i = 0; i < components.length; i++) {
                assertThat(mappings.get(i).getColumn()).isEqualTo(components[i].getName());
                assertThat(mappings.get(i).getJavaType()).isEqualTo(components[i].getType());
            }
        }
    }
}
