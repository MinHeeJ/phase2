package com.sicost.partchanges;

import static org.assertj.core.api.Assertions.*;
import static com.sicost.partchanges.PartChangeModels.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class PartChangeMapperTest {
    Configuration configuration() throws Exception {
        var configuration = new Configuration();
        String path = "mappers/partchanges/PartChangeMapper.xml";
        try (var input = new ClassPathResource(path).getInputStream()) {
            new XMLMapperBuilder(input, configuration, path, configuration.getSqlFragments()).parse();
        }
        return configuration;
    }
    @Test void everyMapperMethodResolvesToAnXmlStatement() throws Exception {
        var configuration = configuration();
        for (var method : PartChangeMapper.class.getDeclaredMethods()) {
            assertThat(configuration.hasStatement(PartChangeMapper.class.getName() + "." + method.getName()))
                .as(method.getName()).isTrue();
        }
    }
    @Test void upsertBindsOnlyRecordPropertiesAndUsesThePhysicalUniqueKey() throws Exception {
        var statement = configuration().getMappedStatement(PartChangeMapper.class.getName() + ".upsert");
        var write = new PartChangeWrite(37, new PartChangeRequest(12L, "2026-10", "변경"), null);
        var bound = statement.getBoundSql(write);
        assertThat(bound.getSql()).contains("ON CONFLICT (prj_id, aply_bgn_ym) DO UPDATE", "chg_at = CURRENT_TIMESTAMP");
        assertThat(bound.getParameterMappings()).extracting(p -> p.getProperty())
            .containsExactly("id", "input.aply_bgn_ym", "input.bsn_part_id", "input.rsn_desc", "actor");
        var meta = configuration().newMetaObject(write);
        assertThat(bound.getParameterMappings().stream().map(p -> meta.getValue(p.getProperty())).toList())
            .containsExactly(37L, "2026-10", 12L, "변경", null);
    }
    @Test void listMaterializationHasAnExplicitConstructorForNullableHistoryAndName() throws Exception {
        var map = configuration().getResultMap(PartChangeMapper.class.getName() + ".historyRow");
        assertThat(map.getType()).isEqualTo(PartChange.class);
        assertThat(map.getConstructorResultMappings()).extracting(m -> m.getJavaType().getName())
            .containsExactly("java.lang.Long", "java.lang.String", "java.lang.String", "java.lang.Long", "java.time.LocalDateTime", "java.lang.String");
        var list = configuration().getMappedStatement(PartChangeMapper.class.getName() + ".list").getBoundSql(37L).getSql();
        assertThat(list).contains("LEFT JOIN mst.bsn_part", "AS \"partName\"", "WHERE h.prj_id =", "ORDER BY h.aply_bgn_ym ASC")
            .doesNotContain("LIMIT", "OFFSET");
    }
    @Test void representativeUsesGreatestMonthAndAuditIsScopedToProject() throws Exception {
        var configuration = configuration();
        var write = new PartChangeWrite(37, new PartChangeRequest(12L, "2026-10", "변경"), null);
        assertThat(configuration.getMappedStatement(PartChangeMapper.class.getName() + ".updateRepresentative")
            .getBoundSql(write).getSql()).contains("ORDER BY h.aply_bgn_ym DESC LIMIT 1", "WHERE p.prj_id =");
        var audit = configuration.getMappedStatement(PartChangeMapper.class.getName() + ".audit")
            .getBoundSql(new PartChangeAudit("37", "이전", "이후", "변경", null));
        assertThat(audit.getSql()).contains("'prj.prj_part_hist'", "bfr_val, aft_val, rsn_desc, chg_user_id");
        assertThat(audit.getParameterMappings()).extracting(p -> p.getProperty())
            .containsExactly("id", "before", "after", "reason", "actor");
    }
}
