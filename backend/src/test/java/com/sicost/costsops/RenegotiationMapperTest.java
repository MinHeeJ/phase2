package com.sicost.costsops;

import java.time.LocalDate;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.*;
import static com.sicost.costsops.RenegotiationModels.*;

class RenegotiationMapperTest {
    Configuration configuration() throws Exception {
        var c = new Configuration();
        c.setMapUnderscoreToCamelCase(true);
        c.setArgNameBasedConstructorAutoMapping(true);
        String path = "mappers/costsops/RenegotiationMapper.xml";
        try (var input = new ClassPathResource(path).getInputStream()) {
            new XMLMapperBuilder(input, c, path, c.getSqlFragments()).parse();
        }
        return c;
    }
    @Test void everyStatementAndConstructorLabelTypeAndOrderMatchesWithoutDatabase() throws Exception {
        var c = configuration();
        String ns = RenegotiationMapper.class.getName() + ".";
        for (var method : RenegotiationMapper.class.getDeclaredMethods()) {
            assertThat(c.hasStatement(ns + method.getName())).isTrue();
        }
        var components = Source.class.getRecordComponents();
        var mappings = c.getResultMap(ns + "source").getConstructorResultMappings();
        assertThat(mappings).hasSize(components.length);
        for (int i = 0; i < components.length; i++) {
            assertThat(mappings.get(i).getColumn()).isEqualTo(components[i].getName());
            assertThat(mappings.get(i).getJavaType()).isEqualTo(components[i].getType());
        }
    }
    @Test void allEligibilityRulesUseBoundClockAndOptionalPredicatesAreDynamic() throws Exception {
        var statement = configuration().getMappedStatement(RenegotiationMapper.class.getName() + ".candidates");
        var today = LocalDate.of(2026,10,29);
        var absent = statement.getBoundSql(new SourceQuery(today,today.plusMonths(6),"2026-10",null,null));
        assertThat(absent.getSql()).contains("p.prj_stts_cd='RUN'", "p.cntrct_ty_cd IN ('MO','DP')",
            "a.asgn_stts_cd='ACTIVE'", "a.asgn_se_cd!='FREE'", "a.end_dt >=?", "a.end_dt <=?", "a.bgn_dt <=?")
            .doesNotContain("CURRENT_DATE", "IS NULL OR", "cntrct_dev_id", "INSERT");
        assertThat(absent.getParameterMappings()).extracting(m -> m.getProperty())
            .doesNotContain("empType", "projectId");
        var present = statement.getBoundSql(new SourceQuery(today,today.plusMonths(3),"2026-10","FL",42L));
        assertThat(present.getParameterMappings()).extracting(m -> m.getProperty()).contains("empType", "projectId");
    }
}
