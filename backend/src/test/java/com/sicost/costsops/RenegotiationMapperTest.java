package com.sicost.costsops;

import java.time.LocalDate;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import static com.sicost.costsops.RenegotiationModels.*;
import static org.assertj.core.api.Assertions.*;

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
    @Test void projectionMatchesEveryRecordLabelTypeAndConstructorPositionWithoutDatabase() throws Exception {
        var c = configuration();
        String ns = RenegotiationMapper.class.getName() + ".";
        for (var method : RenegotiationMapper.class.getDeclaredMethods()) assertThat(c.hasStatement(ns + method.getName())).isTrue();
        var components = Candidate.class.getRecordComponents();
        var mappings = c.getResultMap(ns + "candidate").getConstructorResultMappings();
        assertThat(mappings).hasSize(components.length);
        for (int i = 0; i < components.length; i++) {
            assertThat(mappings.get(i).getColumn()).isEqualTo(components[i].getName());
            assertThat(mappings.get(i).getJavaType()).isEqualTo(components[i].getType());
        }
    }
    @Test void populationRequiresInclusiveCurrentActivePaidRunningMonthlyOrDispatchAssignment() throws Exception {
        var c = configuration();
        var input = new Population(LocalDate.parse("2026-10-29"), LocalDate.parse("2027-04-29"), null, null);
        var bound = c.getMappedStatement(RenegotiationMapper.class.getName() + ".candidates").getBoundSql(input);
        assertThat(bound.getSql()).contains("a.asgn_stts_cd='ACTIVE'", "a.asgn_se_cd!='FREE'",
            "p.prj_stts_cd='RUN'", "p.cntrct_ty_cd IN ('MO','DP')", "a.bgn_dt <= ?", "a.end_dt >= ?",
            "a.end_dt <= ?", "h.aply_bgn_dt <= ?", "ORDER BY h.aply_bgn_dt DESC")
            .doesNotContain("bill.bill", "bill_dtl", "vat_amt", "cntrct_dev_id", " IS NULL OR ");
        assertThat(bound.getParameterMappings()).extracting(p -> p.getProperty()).doesNotContain("empType", "projectId");
    }
    @Test void optionalPopulationFiltersBindOnlyPresentValuesAndNoValuesAreConcatenated() throws Exception {
        var statement = configuration().getMappedStatement(RenegotiationMapper.class.getName() + ".candidates");
        var one = statement.getBoundSql(new Population(LocalDate.parse("2026-10-29"), LocalDate.parse("2027-04-29"), "FT", 31L));
        assertThat(one.getSql()).contains("d.emp_se_cd=?", "a.prj_id=?");
        assertThat(one.getParameterMappings()).extracting(p -> p.getProperty()).contains("empType", "projectId");
    }
    @Test void auditUsesCorrectScreenAndNullableCurrentActorWithoutSourceMutation() throws Exception {
        var statement = configuration().getMappedStatement(RenegotiationMapper.class.getName() + ".download");
        var sql = statement.getBoundSql(new Access("list.xlsx", "{}", null)).getSql();
        assertThat(sql).contains("INSERT INTO sys.acs_hist", "'DOWNLOAD','UI-32'")
            .doesNotContain("UPDATE", "cst_aloc", "untprc_hist");
    }
}
