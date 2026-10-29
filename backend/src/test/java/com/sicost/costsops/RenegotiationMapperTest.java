package com.sicost.costsops;

import java.time.LocalDate;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import static com.sicost.costsops.RenegotiationModels.*;
import static org.assertj.core.api.Assertions.*;

class RenegotiationMapperTest {
    static Configuration configuration() throws Exception {
        var c = new Configuration();
        c.setMapUnderscoreToCamelCase(true);
        c.setArgNameBasedConstructorAutoMapping(true);
        String path = "mappers/costsops/RenegotiationMapper.xml";
        try (var input = new ClassPathResource(path).getInputStream()) {
            new XMLMapperBuilder(input,c,path,c.getSqlFragments()).parse();
        }
        return c;
    }
    @Test void explicitProjectionMatchesRecordOrderNamesAndTypesWithoutDatabase() throws Exception {
        var mappings = configuration().getResultMap(RenegotiationMapper.class.getName()+".candidate").getConstructorResultMappings();
        var fields = Candidate.class.getRecordComponents();
        assertThat(mappings).hasSize(fields.length);
        for (int i=0;i<fields.length;i++) {
            assertThat(mappings.get(i).getColumn()).isEqualTo(fields[i].getName());
            assertThat(mappings.get(i).getJavaType()).isEqualTo(fields[i].getType());
        }
    }
    @Test void optionalPredicatesOnlyBindPresentValuesAndNeverLimitDenominators() throws Exception {
        var statement = configuration().getMappedStatement(RenegotiationMapper.class.getName()+".candidates");
        LocalDate today = LocalDate.of(2026,10,23);
        var unfiltered = statement.getBoundSql(new CandidateQuery(today,today.plusMonths(6),"2026-10",null,null));
        var filtered = statement.getBoundSql(new CandidateQuery(today,today.plusMonths(3),"2026-10","FT",11L));
        assertThat(unfiltered.getParameterMappings()).extracting(m -> m.getProperty())
            .containsExactly("month","today","today","through");
        assertThat(filtered.getParameterMappings()).extracting(m -> m.getProperty())
            .containsExactly("month","today","today","through","empType","projectId");
        assertThat(unfiltered.getSql()).contains("p.prj_stts_cd='RUN'", "('MO','DP')", "a.asgn_stts_cd='ACTIVE'",
            "a.asgn_se_cd!='FREE'", "p.free_sprt_yn='N'").doesNotContain("IS NULL OR", "LIMIT", "sum(");
        assertThat(statement.getSqlCommandType()).isEqualTo(org.apache.ibatis.mapping.SqlCommandType.SELECT);
    }
}
