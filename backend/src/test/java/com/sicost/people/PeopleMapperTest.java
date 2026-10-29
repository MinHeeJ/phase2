package com.sicost.people;

import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.*;
import static com.sicost.people.PeopleModels.*;

class PeopleMapperTest {
    Configuration configuration() throws Exception {
        var c = new Configuration();
        c.setMapUnderscoreToCamelCase(true);
        c.setArgNameBasedConstructorAutoMapping(true);
        String path = "mappers/people/PeopleMapper.xml";
        try (var input = new ClassPathResource(path).getInputStream()) {
            new XMLMapperBuilder(input, c, path, c.getSqlFragments()).parse();
        }
        return c;
    }
    @Test void projectionsMatchConstructorLabelsTypesAndOrderWithoutDatabase() throws Exception {
        var c = configuration();
        String ns = PeopleMapper.class.getName() + ".";
        for (var method : PeopleMapper.class.getDeclaredMethods()) assertThat(c.hasStatement(ns + method.getName())).isTrue();
        for (var entry : Map.of("personMonth", PersonMonth.class, "projectLink", ProjectLink.class).entrySet()) {
            var components = entry.getValue().getRecordComponents();
            var mappings = c.getResultMap(ns + entry.getKey()).getConstructorResultMappings();
            assertThat(mappings).hasSize(components.length);
            for (int i = 0; i < components.length; i++) {
                assertThat(mappings.get(i).getColumn()).isEqualTo(components[i].getName());
                assertThat(mappings.get(i).getJavaType()).isEqualTo(components[i].getType());
            }
        }
        var bound = c.getMappedStatement(ns + "people").getBoundSql(new Period("2026-01", "2026-02"));
        assertThat(bound.getParameterMappings()).allMatch(p -> p.getProperty().equals("fromMonth") || p.getProperty().equals("throughMonth"));
        assertThat(bound.getSql()).contains("GROUP BY e.dev_id,e.expns_ym", "CROSS JOIN months");
    }
}
