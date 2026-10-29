package com.sicost.parts;

import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.*;
import static com.sicost.parts.PartSettlementModels.*;

class PartSettlementMapperTest {
    Configuration configuration() throws Exception {
        var c = new Configuration();
        c.setMapUnderscoreToCamelCase(true); c.setArgNameBasedConstructorAutoMapping(true);
        String path = "mappers/parts/PartSettlementMapper.xml";
        try (var input = new ClassPathResource(path).getInputStream()) {
            new XMLMapperBuilder(input, c, path, c.getSqlFragments()).parse();
        }
        return c;
    }
    @Test void projectionsMatchRecordConstructorLabelsTypesAndOrderWithoutDatabase() throws Exception {
        var c = configuration(); String ns = PartSettlementMapper.class.getName() + ".";
        for (var method : PartSettlementMapper.class.getDeclaredMethods()) assertThat(c.hasStatement(ns + method.getName())).isTrue();
        var projections = Map.of("part", Part.class, "standard", Standard.class, "salary", Salary.class,
            "header", Header.class, "person", Person.class, "history", History.class);
        for (var entry : projections.entrySet()) {
            var components = entry.getValue().getRecordComponents();
            var mappings = c.getResultMap(ns + entry.getKey()).getConstructorResultMappings();
            assertThat(mappings).hasSize(components.length);
            for (int i = 0; i < components.length; i++) {
                assertThat(mappings.get(i).getColumn()).isEqualTo(components[i].getName());
                assertThat(mappings.get(i).getJavaType()).isEqualTo(components[i].getType());
            }
        }
    }
    @Test void parentAndSettlementLocksAreUncachedAndDraftUsesReturnedKey() throws Exception {
        var c = configuration(); String ns = PartSettlementMapper.class.getName() + ".";
        for (String method : List.of("lockPart", "lockHeader", "lockMonth")) {
            var statement = c.getMappedStatement(ns + method);
            assertThat(statement.isUseCache()).isFalse(); assertThat(statement.isFlushCacheRequired()).isTrue();
            assertThat(statement.getBoundSql(new Key(3, "2026-10")).getSql()).contains("FOR UPDATE");
        }
        assertThat(c.getMappedStatement(ns + "insertDraft").getBoundSql(new Key(3, "2026-10")).getSql())
            .contains("RETURNING stlm_id").doesNotContain("JOIN");
    }
}
