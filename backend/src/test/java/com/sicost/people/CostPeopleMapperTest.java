package com.sicost.people;

import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.*;
import static com.sicost.people.CostPeopleModels.*;

class CostPeopleMapperTest {
    Configuration configuration() throws Exception {
        var c = new Configuration();
        c.setMapUnderscoreToCamelCase(true);
        c.setArgNameBasedConstructorAutoMapping(true);
        String path = "mappers/people/CostPeopleMapper.xml";
        try (var input = new ClassPathResource(path).getInputStream()) {
            new XMLMapperBuilder(input, c, path, c.getSqlFragments()).parse();
        }
        return c;
    }
    @Test void everyProjectionMatchesRecordLabelsTypesAndOrderWithoutDatabase() throws Exception {
        var c = configuration();
        String ns = CostPeopleMapper.class.getName() + ".";
        for (var method : CostPeopleMapper.class.getDeclaredMethods()) assertThat(c.hasStatement(ns + method.getName())).isTrue();
        for (var e : Map.of("person", Person.class, "payroll", Payroll.class, "project", Project.class).entrySet()) {
            var components = e.getValue().getRecordComponents();
            var mappings = c.getResultMap(ns + e.getKey()).getConstructorResultMappings();
            assertThat(mappings).hasSize(components.length);
            for (int i = 0; i < components.length; i++) {
                assertThat(mappings.get(i).getColumn()).isEqualTo(components[i].getName());
                assertThat(mappings.get(i).getJavaType()).isEqualTo(components[i].getType());
            }
        }
    }
    @Test void payrollComponentsAreAggregatedBeforeJoiningAndOnlyAuditWrites() throws Exception {
        var c = configuration();
        String ns = CostPeopleMapper.class.getName() + ".";
        var sql = c.getMappedStatement(ns + "payroll").getBoundSql("2026-10").getSql();
        assertThat(sql).contains("LEFT JOIN LATERAL", "x.dev_id=d.dev_id", "s.indv_lbr_cst_amt")
            .doesNotContain("vat_amt", "asgn.asgn", " IS NULL OR ");
        assertThat(c.getMappedStatement(ns + "download").getBoundSql(new Access("file.xlsx", "{}", null)).getSql())
            .contains("sys.acs_hist", "'DOWNLOAD'", "'UI-03'");
    }
}
